package com.topo.discovery.service;

import com.topo.discovery.collector.SnmpCollector;
import com.topo.discovery.collector.SshCommandExecutor;
import com.topo.discovery.dto.TopologyGraphResponse;
import com.topo.discovery.model.*;
import com.topo.discovery.repository.DeviceRepository;
import com.topo.discovery.repository.LinkRepository;
import com.topo.discovery.repository.NetworkInterfaceRepository;
import com.topo.discovery.vendor.VendorAdapter;
import com.topo.discovery.vendor.VendorAdapterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor BFS de discovery.
 *
 * Fix-uri fata de versiunea anterioara:
 * 1. NullPointerException in ConcurrentHashMap (nu punem valori null)
 * 2. Vendor detection prin SNMP sysDescr (fallback pentru orice device)
 * 3. Link-uri extrase corect din LLDP - rezolvam vecinii prin hostname match
 * 4. Events WebSocket progresive (NODE_DISCOVERED, LINK_DISCOVERED)
 * 5. Fara bootstrap config - SNMP/LLDP sunt deja active
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryEngineService {

    private final DeviceRepository deviceRepository;
    private final NetworkInterfaceRepository interfaceRepository;
    private final LinkRepository linkRepository;
    private final DeviceService deviceService;
    private final SshCommandExecutor sshExecutor;
    private final SnmpCollector snmpCollector;
    private final VendorAdapterFactory vendorAdapterFactory;
    private final DiscoveryProgressNotifier progressNotifier;

    @Value("${discovery.bfs.max-depth}")
    private int maxDepth;

    @Value("${discovery.bfs.max-devices}")
    private int maxDevices;

    @Value("${discovery.bfs.parallel-threads:5}")
    private int parallelThreads;

    @Value("${discovery.snmp.community}")
    private String defaultCommunity;

    private static final int SSH_PORT = 22;

    // starea ultimei rulari - FARA valori null (ConcurrentHashMap nu accepta null)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicInteger devicesProcessed = new AtomicInteger(0);
    private volatile String lastError = "";
    private volatile String startedAt = "";
    private volatile String finishedAt = "";

    public boolean requestStop() {
        if (!running.get()) return false;
        stopRequested.set(true);
        return true;
    }

    @Async
    public void runDiscoveryAsync(List<Long> seedDeviceIds) {
        if (running.getAndSet(true)) {
            log.warn("Discovery deja in curs , ignoram request-ul nou");
            return;
        }
        stopRequested.set(false);
        devicesProcessed.set(0);
        lastError = "";
        startedAt = LocalDateTime.now().toString();
        finishedAt = "";

        try {
            runBfs(seedDeviceIds);

            // Rezolvam placeholder-urile lldp:* create in timpul BFS-ului paralel.
            // La procesare paralela, device-ul X poate fi descoperit via LLDP de Y
            // inainte ca X sa-si salveze hostname-ul in DB -> se creeaza un placeholder.
            // Dupa ce toate device-urile sunt procesate, putem face match hostname -> IP real.
            resolvePlaceholders();

            finishedAt = LocalDateTime.now().toString();
            int totalLinks = linkRepository.findAll().size();
            if (stopRequested.get()) {
                log.info("Discovery oprit manual dupa {} device-uri", devicesProcessed.get());
                progressNotifier.notifyStopped(devicesProcessed.get(), totalLinks);
            } else {
                progressNotifier.notifyCompleted(devicesProcessed.get(), totalLinks);
            }
        } catch (Exception e) {
            log.error("Discovery BFS esuat: {}", e.getMessage(), e);
            lastError = e.getMessage() != null ? e.getMessage() : "Eroare necunoscuta";
            progressNotifier.notifyError(lastError);
        } finally {
            running.set(false);
        }
    }

    public Map<String, Object> getLastRunStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        status.put("devicesProcessed", devicesProcessed.get());
        status.put("startedAt", startedAt);
        status.put("finishedAt", finishedAt);
        status.put("lastError", lastError);
        return status;
    }

    private void runBfs(List<Long> seedDeviceIds) {
        Set<Long> visited = ConcurrentHashMap.newKeySet();
        visited.addAll(seedDeviceIds);

        List<Long> currentLevel = new ArrayList<>(seedDeviceIds);
        int depth = 0;

        ExecutorService bfsPool = Executors.newFixedThreadPool(parallelThreads);
        try {
            while (!currentLevel.isEmpty() && devicesProcessed.get() < maxDevices
                    && !stopRequested.get() && depth <= maxDepth) {

                log.info("BFS nivel {}: {} device-uri in paralel", depth, currentLevel.size());

                // captura finala pentru lambda (depth e modificat in bucla)
                final int currentDepth = depth;

                // trimitem toate device-urile de pe nivelul curent in paralel
                List<Future<List<Device>>> futures = new ArrayList<>();
                for (Long deviceId : currentLevel) {
                    futures.add(bfsPool.submit(() -> {
                        Device device = deviceRepository.findById(deviceId).orElse(null);
                        if (device == null) return List.of();
                        log.info("BFS procesez: {} (depth={})", device.getManagementIp(), currentDepth);
                        progressNotifier.notifyProcessing(device.getManagementIp(), "POLLING");
                        List<Device> neighbors = processDevice(device);
                        devicesProcessed.incrementAndGet();
                        return neighbors;
                    }));
                }

                // colectam vecinii pentru nivelul urmator
                List<Long> nextLevel = new ArrayList<>();
                for (Future<List<Device>> future : futures) {
                    try {
                        List<Device> neighbors = future.get(120, TimeUnit.SECONDS);
                        for (Device n : neighbors) {
                            if (visited.add(n.getId())
                                    && devicesProcessed.get() + nextLevel.size() < maxDevices) {
                                nextLevel.add(n.getId());
                            }
                        }
                    } catch (TimeoutException e) {
                        log.warn("BFS worker timeout la nivelul {}", depth);
                    } catch (Exception e) {
                        log.error("Eroare in BFS worker nivel {}: {}", depth, e.getMessage());
                    }
                }

                currentLevel = nextLevel;
                depth++;
            }
        } finally {
            bfsPool.shutdown();
        }

        log.info("BFS finalizat. Procesate: {} device-uri", devicesProcessed.get());
    }

    public List<Device> processDevice(Device device) {
        List<Device> newNeighbors = new ArrayList<>();
        String sshPassword  = deviceService.decryptSshPassword(device);
        String snmpCommunity = deviceService.decryptSnmpCommunity(device);

        try {
            device.setStatus(DeviceStatus.POLLING);
            deviceRepository.save(device);

            // Pasul 1: detectie vendor prin SNMP sysDescr (functioneaza pe orice device)
            String sysDescr = snmpCollector.getSysDescr(device.getManagementIp(), snmpCommunity);
            if (sysDescr != null) {
                device.setSysDescr(sysDescr.length() > 1000 ? sysDescr.substring(0, 1000) : sysDescr);
                // daca vendor-ul era UNKNOWN, incearca sa-l detecteze din sysDescr
                if (device.getVendor() == Vendor.UNKNOWN || device.getVendor() == null) {
                    Vendor detected = Vendor.detect(sysDescr);
                    device.setVendor(detected);
                    log.info("Vendor detectat pentru {} din sysDescr: {}", device.getManagementIp(), detected);
                }
            }

            // hostname din SNMP (mai rapid si mai fiabil decat SSH pe unele device-uri)
            String sysName = snmpCollector.getSysName(device.getManagementIp(), snmpCommunity);
            if (sysName != null && !sysName.isBlank()) {
                device.setHostname(sysName.trim());
            }

            // Pasul 2: SSH pentru model/version (doar daca vendor e cunoscut)
            if (device.getVendor() != Vendor.UNKNOWN && sshPassword != null) {
                pollViaSsh(device, sshPassword);
            }

            // Pasul 3: interfete prin SNMP
            pollInterfaces(device, snmpCommunity);

            // salveaza device-ul cu toate datele noi si notifica frontend-ul
            device.setStatus(DeviceStatus.ACTIVE);
            device.setLastPolledAt(LocalDateTime.now());
            device.setLastError(null);
            device = deviceRepository.save(device);

            progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(device));

            // Pasul 4: LLDP neighbors - sursa principala de link-uri
            List<SnmpCollector.LldpNeighbor> lldpNeighbors =
                    snmpCollector.walkLldpNeighbors(device.getManagementIp(), snmpCommunity);

            log.info("LLDP: {} vecini pe {}", lldpNeighbors.size(), device.getManagementIp());

            for (SnmpCollector.LldpNeighbor neighbor : lldpNeighbors) {
                Device neighborDevice = resolveOrCreateNeighbor(neighbor, device, sshPassword, snmpCommunity);
                createLink(device, neighbor, neighborDevice);
                // Adaugam in BFS doar daca e un IP real, nu un placeholder lldp:*
                // Placeholder-urile nu au IP valid si SNMP/SSH vor esua garantat
                if (neighborDevice != null
                        && neighborDevice.getStatus() == DeviceStatus.DISCOVERED
                        && !neighborDevice.getManagementIp().startsWith("lldp:")) {
                    newNeighbors.add(neighborDevice);
                }
            }

            // Pasul 5: ARP fallback - imbogateste interfetele cu IP-uri
            enrichWithArp(device, snmpCommunity);

        } catch (Exception e) {
            log.error("Eroare la procesarea {}: {}", device.getManagementIp(), e.getMessage());
            device.setStatus(DeviceStatus.ERROR);
            device.setLastError(e.getMessage() != null ? e.getMessage() : "Eroare necunoscuta");
            deviceRepository.save(device);
            progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(device));
        }

        return newNeighbors;
    }

    private void pollViaSsh(Device device, String sshPassword) {
        try {
            VendorAdapter adapter = vendorAdapterFactory.getAdapter(device.getVendor());
            String cmd = adapter.getShowVersionCommand();
            log.debug("SSH [{}] catre {}: comanda '{}'",
                    device.getVendor(), device.getManagementIp(), cmd);

            String versionOutput = sshExecutor.executeCommand(
                    device.getManagementIp(), SSH_PORT,
                    device.getSshUsername(), sshPassword, cmd);

            log.debug("SSH output de la {} ({} chars): {}",
                    device.getManagementIp(),
                    versionOutput.length(),
                    versionOutput.length() > 300 ? versionOutput.substring(0, 300) + "..." : versionOutput);

            VendorAdapter.ParsedVersionInfo info = adapter.parseVersionOutput(versionOutput);
            if (info.hostname() != null && device.getHostname() == null) {
                device.setHostname(info.hostname());
            }
            if (info.model() != null) device.setModel(info.model());
            if (info.osVersion() != null) device.setOsVersion(info.osVersion());
            if (info.serialNumber() != null) device.setSerialNumber(info.serialNumber());

            if (device.getVendor() == com.topo.discovery.model.Vendor.MIKROTIK) {
                log.info("MikroTik SSH {} -> hostname={} model={} version={}",
                        device.getManagementIp(), info.hostname(), info.model(), info.osVersion());
            }
        } catch (Exception e) {
            log.warn("SSH poll esuat pe {} [{}]: {}",
                    device.getManagementIp(), device.getVendor(), e.getMessage());
        }
    }

    private void pollInterfaces(Device device, String snmpCommunity) {
        List<SnmpCollector.InterfaceEntry> entries =
                snmpCollector.walkInterfaces(device.getManagementIp(), snmpCommunity);

        for (SnmpCollector.InterfaceEntry entry : entries) {
            if (entry.getName() == null || entry.getName().isBlank()) continue;

            NetworkInterface iface = interfaceRepository
                    .findFirstByDeviceAndName(device, entry.getName())
                    .orElse(NetworkInterface.builder().device(device).name(entry.getName()).build());

            iface.setMacAddress(entry.getMacAddress());
            iface.setAdminStatus(entry.getAdminStatus());
            iface.setOperStatus(entry.getOperStatus());
            iface.setSpeedMbps(entry.getSpeedMbps());
            iface.setDescription(entry.getDescription());
            if (entry.getIpAddress() != null) iface.setIpAddress(entry.getIpAddress());
            if (entry.getPrefixLength() != null) iface.setPrefixLength(entry.getPrefixLength());

            interfaceRepository.save(iface);
        }
    }

    /**
     * Rezolva un vecin LLDP la un Device din DB sau il creeaza nou.
     *
     * Strategia:
     * 1. Cauta dupa hostname (sysName) - cel mai fiabil
     * 2. Cauta dupa chassis ID (MAC) in interfetele cunoscute
     * 3. Daca nu gaseste, creeaza un Device nou cu IP necunoscut (va fi rezolvat
     *    cand scan-ul de subnet il va gasi, sau din ARP)
     */
    private Device resolveOrCreateNeighbor(SnmpCollector.LldpNeighbor neighbor,
                                            Device sourceDevice,
                                            String sshPassword,
                                            String snmpCommunity) {
        if (neighbor.getRemoteSystemName() == null) return null;
        String remoteName = neighbor.getRemoteSystemName().trim();

        // 1. cauta dupa hostname exact sau management IP
        Optional<Device> byHostname = deviceRepository.findFirstByHostnameIgnoreCase(remoteName);
        if (byHostname.isEmpty()) {
            byHostname = deviceRepository.findByManagementIp(remoteName);
        }
        if (byHostname.isPresent()) return byHostname.get();

        // 2. cauta dupa chassis MAC in interfete
        if (neighbor.getRemoteChassisId() != null) {
            Optional<NetworkInterface> byMac =
                    interfaceRepository.findFirstByMacAddress(neighbor.getRemoteChassisId());
            if (byMac.isPresent()) return byMac.get().getDevice();
        }

        // 3. device nou - IP necunoscut, hostname din LLDP
        // Il cream ca placeholder cu IP "lldp:<hostname>" pana cand scan-ul il rezolva
        String placeholderIp = "lldp:" + remoteName;
        Optional<Device> existing = deviceRepository.findByManagementIp(placeholderIp);
        if (existing.isPresent()) return existing.get();

        log.info("Vecin LLDP nou necunoscut: {} - cream placeholder", remoteName);
        Device placeholder = Device.builder()
                .managementIp(placeholderIp)
                .hostname(remoteName)
                .vendor(Vendor.UNKNOWN)
                .status(DeviceStatus.DISCOVERED)
                .seedDevice(false)
                .sshUsername(sourceDevice.getSshUsername())
                .sshPasswordEncrypted(sourceDevice.getSshPasswordEncrypted())
                .snmpCommunityEncrypted(sourceDevice.getSnmpCommunityEncrypted())
                .build();
        placeholder = deviceRepository.save(placeholder);

        // notifica frontend-ul imediat de nodul nou
        progressNotifier.notifyNodeDiscovered(GraphBuilderService.toNode(placeholder));
        return placeholder;
    }

    private void createLink(Device localDevice, SnmpCollector.LldpNeighbor neighbor, Device remoteDevice) {
        // Deduplicare pe PERECHE de device-uri (nu pe port individual).
        // Cazul bonding/LAG: ge-0/0/1 si ge-0/0/2 duc ambele la spine01 ->
        // tinem un singur link logic intre core01 si spine01, indiferent de cate porturi fizice.
        if (remoteDevice != null) {
            boolean pairExists = linkRepository.existsByLocalDeviceAndRemoteDevice(localDevice, remoteDevice)
                    || linkRepository.existsByLocalDeviceAndRemoteDevice(remoteDevice, localDevice);
            if (pairExists) {
                log.debug("Link deja existent intre {} si {}, skip (bonding/LAG)",
                        localDevice.getManagementIp(), remoteDevice.getManagementIp());
                return;
            }
        } else {
            // remoteDevice null: deduplicam dupa (localDevice, remoteSystemName)
            boolean nameExists = linkRepository.findByLocalDevice(localDevice).stream()
                    .anyMatch(l -> neighbor.getRemoteSystemName() != null
                            && neighbor.getRemoteSystemName().equals(l.getRemoteSystemName()));
            if (nameExists) return;
        }

        Link link = Link.builder()
                .localDevice(localDevice)
                .localInterfaceName(neighbor.getLocalPortId())
                .remoteDevice(remoteDevice)
                .remoteInterfaceName(neighbor.getRemotePortId())
                .remoteSystemName(neighbor.getRemoteSystemName())
                .remoteChassisId(neighbor.getRemoteChassisId())
                .source(Link.DiscoverySource.LLDP)
                .build();
        link = linkRepository.save(link);

        // notifica frontend daca avem ambele capete cunoscute
        if (remoteDevice != null) {
            progressNotifier.notifyLinkDiscovered(
                    TopologyGraphResponse.GraphEdge.builder()
                            .id("link-" + link.getId())
                            .source(String.valueOf(localDevice.getId()))
                            .target(String.valueOf(remoteDevice.getId()))
                            .sourceInterface(neighbor.getLocalPortId())
                            .targetInterface(neighbor.getRemotePortId())
                            .discoverySource("LLDP")
                            .build()
            );
        }
    }

    private void enrichWithArp(Device device, String snmpCommunity) {
        List<SnmpCollector.ArpEntry> arpEntries =
                snmpCollector.walkArpTable(device.getManagementIp(), snmpCommunity);

        for (SnmpCollector.ArpEntry arp : arpEntries) {
            if (arp.getMacAddress() == null) continue;
            interfaceRepository.findFirstByMacAddress(arp.getMacAddress()).ifPresent(iface -> {
                if (iface.getIpAddress() == null) {
                    iface.setIpAddress(arp.getIpAddress());
                    interfaceRepository.save(iface);
                }
            });
        }
    }

    /**
     * Rezolva placeholder-urile "lldp:<hostname>" create in BFS-ul paralel.
     *
     * Problema: la procesare paralela, device-ul A descoperit prin LLDP de B poate
     * sa nu aiba inca hostname-ul salvat in DB in momentul in care B il cauta.
     * Rezultat: se creeaza un placeholder "lldp:A" duplicat langa device-ul real A.
     *
     * Solutia: dupa ce BFS termina si toate hostname-urile sunt scrise,
     * facem un pas de rezolutie: gasim device-ul real pentru fiecare placeholder,
     * mutam link-urile, stergem placeholder-ul.
     */
    private void resolvePlaceholders() {
        List<Device> placeholders = deviceRepository.findAll().stream()
                .filter(d -> d.getManagementIp().startsWith("lldp:"))
                .toList();

        if (placeholders.isEmpty()) return;
        log.info("Rezolvare {} placeholder-uri lldp:*", placeholders.size());

        for (Device placeholder : placeholders) {
            String hostname = placeholder.getHostname();
            if (hostname == null || hostname.isBlank()) continue;

            // Cauta device-ul real dupa hostname (a fost setat in BFS dupa ce s-a terminat procesarea)
            deviceRepository.findFirstByHostnameIgnoreCase(hostname).ifPresent(real -> {
                if (real.getId().equals(placeholder.getId())) return; // acelasi device
                if (real.getManagementIp().startsWith("lldp:")) return; // tot placeholder

                log.info("Rezolvare placeholder {} -> {} ({})",
                        placeholder.getManagementIp(), real.getHostname(), real.getManagementIp());

                // Muta link-urile de la placeholder la device-ul real
                linkRepository.findByLocalDevice(placeholder).forEach(link -> {
                    // evita link self-loop dupa mutare
                    if (!link.getRemoteDevice().equals(real)) {
                        link.setLocalDevice(real);
                        linkRepository.save(link);
                    } else {
                        linkRepository.delete(link);
                    }
                });

                linkRepository.findByRemoteDevice(placeholder).forEach(link -> {
                    if (!link.getLocalDevice().equals(real)) {
                        link.setRemoteDevice(real);
                        linkRepository.save(link);
                    } else {
                        linkRepository.delete(link);
                    }
                });

                // Sterge placeholder-ul si interfetele lui
                interfaceRepository.findByDevice(placeholder).forEach(interfaceRepository::delete);
                deviceRepository.delete(placeholder);

                // Notifica frontend: nodul placeholder a fost inlocuit cu cel real
                progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(real));
            });
        }
    }
}
