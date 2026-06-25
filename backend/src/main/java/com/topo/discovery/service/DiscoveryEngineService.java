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
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;
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
        String sshPassword   = deviceService.decryptSshPassword(device);
        String snmpCommunity = deviceService.decryptSnmpCommunity(device);

        // Acumulam warning-uri non-fatale (SSH esuat, SNMP partial, etc.)
        // pentru a le afisa in UI cand userul da click pe device
        List<String> warnings = new ArrayList<>();

        try {
            device.setStatus(DeviceStatus.POLLING);
            deviceRepository.save(device);

            // Pasul 1: detectie vendor prin SNMP sysDescr
            // ATENTIE: getSysDescr() inghite exceptiile intern si returneaza null.
            // Trebuie sa verificam null explicit ca sa adaugam warning.
            String sysDescr = snmpCollector.getSysDescr(device.getManagementIp(), snmpCommunity);
            if (sysDescr == null) {
                warnings.add("[SNMP] sysDescr: timeout sau community gresit (community='"
                        + (snmpCommunity != null ? snmpCommunity : "null") + "')");
                log.warn("SNMP sysDescr null pe {} — SNMP posibil neconfigurat sau community gresit",
                        device.getManagementIp());
            } else {
                // Log sysDescr la INFO ca sa putem diagnostica vendor detection issues
                log.info("sysDescr de la {}: '{}'", device.getManagementIp(),
                        sysDescr.substring(0, Math.min(120, sysDescr.length())).replace("\n", " "));
                device.setSysDescr(sysDescr.length() > 1000 ? sysDescr.substring(0, 1000) : sysDescr);
                if (device.getVendor() == Vendor.UNKNOWN || device.getVendor() == null) {
                    Vendor detected = Vendor.detect(sysDescr);
                    if (detected != Vendor.UNKNOWN) {
                        device.setVendor(detected);
                        log.info("Vendor detectat pentru {} din sysDescr: {}", device.getManagementIp(), detected);
                    } else {
                        log.warn("Vendor nedetectat din sysDescr pe {} — sysDescr: '{}'",
                                device.getManagementIp(),
                                sysDescr.substring(0, Math.min(80, sysDescr.length())));
                    }
                }
            }

            // hostname din SNMP
            String sysName = snmpCollector.getSysName(device.getManagementIp(), snmpCommunity);
            if (sysName != null && !sysName.isBlank()) {
                device.setHostname(sysName.trim());
            } else if (sysDescr == null) {
                // daca si sysName e null, SNMP e complet nefunctional pe acest device
                warnings.add("[SNMP] sysName: indisponibil");
            }

            // Pasul 2: SSH pentru model/version
            if (sshPassword == null) {
                warnings.add("[SSH] Parola SSH lipsa — SSH sarit");
            } else if (device.getVendor() != Vendor.UNKNOWN) {
                // Vendor cunoscut — folosim adapter-ul corespunzator
                String sshErr = pollViaSsh(device, sshPassword);
                if (sshErr != null) warnings.add("[SSH] " + sshErr);
            } else {
                // Vendor UNKNOWN (SNMP a esuat sau nu a detectat) — probam SSH ca sa detectam vendor-ul.
                // Incercam MikroTik, Arista, Juniper in ordine.
                log.info("Vendor UNKNOWN pe {} — probam SSH pentru detectie", device.getManagementIp());
                String probeErr = probeVendorViaSsh(device, sshPassword);
                if (probeErr != null) {
                    warnings.add("[SSH probe] " + probeErr);
                    log.warn("SSH probe esuat pe {}: {}", device.getManagementIp(), probeErr);
                }
            }

            // Pasul 3: interfete prin SNMP (sarim daca SNMP nu raspunde deloc)
            if (sysDescr != null) {
                try {
                    pollInterfaces(device, snmpCommunity);
                } catch (Exception e) {
                    warnings.add("[SNMP] IF-MIB walk esuat: " + e.getMessage());
                    log.warn("IF-MIB walk esuat pe {}: {}", device.getManagementIp(), e.getMessage());
                }
            } else {
                log.info("Sarim IF-MIB + LLDP walk pe {} — SNMP indisponibil", device.getManagementIp());
            }

            // Salveaza device-ul — ACTIVE daca avem cel putin sysDescr sau hostname
            device.setStatus(DeviceStatus.ACTIVE);
            device.setLastPolledAt(LocalDateTime.now());
            // pastram warning-urile ca lastError (nu sunt erori fatale)
            device.setLastError(warnings.isEmpty() ? null : String.join("\n", warnings));
            device = deviceRepository.save(device);

            progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(device));

            // Pasul 4: LLDP neighbors (sarim daca SNMP nu raspunde)
            List<SnmpCollector.LldpNeighbor> lldpNeighbors = List.of();
            if (sysDescr != null) {
                try {
                    lldpNeighbors = snmpCollector.walkLldpNeighbors(device.getManagementIp(), snmpCommunity);
                } catch (Exception e) {
                    warnings.add("[SNMP] LLDP walk esuat: " + e.getMessage());
                    log.warn("LLDP walk esuat pe {}: {}", device.getManagementIp(), e.getMessage());
                }
            }

            log.info("LLDP: {} vecini pe {}", lldpNeighbors.size(), device.getManagementIp());

            for (SnmpCollector.LldpNeighbor neighbor : lldpNeighbors) {
                Device neighborDevice = resolveOrCreateNeighbor(neighbor, device, sshPassword, snmpCommunity);
                createLink(device, neighbor, neighborDevice);
                if (neighborDevice != null
                        && neighborDevice.getStatus() == DeviceStatus.DISCOVERED
                        && !neighborDevice.getManagementIp().startsWith("lldp:")) {
                    newNeighbors.add(neighborDevice);
                }
            }

            // Pasul 5: ARP fallback
            try {
                enrichWithArp(device, snmpCommunity);
            } catch (Exception e) {
                log.debug("ARP walk esuat pe {} (ignorat): {}", device.getManagementIp(), e.getMessage());
            }

        } catch (Exception e) {
            log.error("Eroare fatala la procesarea {}: {}", device.getManagementIp(), e.getMessage());
            warnings.add("[FATAL] " + e.getMessage());
            device.setStatus(DeviceStatus.ERROR);
            device.setLastError(String.join("\n", warnings));
            deviceRepository.save(device);
            progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(device));
        }

        return newNeighbors;
    }

    /**
     * Probeaza SSH pentru a detecta vendor-ul unui device UNKNOWN.
     * Incearca comenzile fiecarui vendor suportat si verifica outputul.
     *
     * Ordinea: MikroTik (cel mai probabil necunoscut), Arista, Juniper.
     * Returneaza null daca vendor-ul a fost detectat si datele completate,
     * sau mesajul de eroare daca niciun vendor nu a raspuns corespunzator.
     */
    /**
     * Probeaza SSH in paralel pentru toti vendor-ii suportati.
     * Primul raspuns valid castiga — mult mai rapid decat secvential.
     */
    private String probeVendorViaSsh(Device device, String sshPassword) {
        Vendor[] vendors = { Vendor.MIKROTIK, Vendor.ARISTA, Vendor.JUNIPER };

        // Lansam toate probele in paralel
        record ProbeResult(Vendor vendor, String output, VendorAdapter adapter) {}

        List<CompletableFuture<ProbeResult>> futures = Arrays.stream(vendors)
                .map(v -> CompletableFuture.supplyAsync(() -> {
                    try {
                        VendorAdapter adapter = vendorAdapterFactory.getAdapter(v);
                        log.debug("SSH probe paralel [{}] catre {}", v, device.getManagementIp());
                        String out = sshExecutor.executeCommand(
                                device.getManagementIp(), SSH_PORT,
                                device.getSshUsername(), sshPassword,
                                adapter.getShowVersionCommand());
                        if (out != null && !out.isBlank()) return new ProbeResult(v, out, adapter);
                    } catch (Exception e) {
                        log.debug("SSH probe [{}] esuat pe {}: {}", v, device.getManagementIp(), e.getMessage());
                    }
                    return null;
                }))
                .toList();

        // Asteptam rezultatele in ordine de prioritate (MikroTik, Arista, Juniper)
        for (int i = 0; i < vendors.length; i++) {
            try {
                ProbeResult pr = futures.get(i).get(12, TimeUnit.SECONDS);
                if (pr == null) continue;

                Vendor v = pr.vendor();
                String out = pr.output();
                boolean matches = switch (v) {
                    case MIKROTIK -> out.contains("board-name") || out.contains("RouterOS") || out.contains("MikroTik");
                    case JUNIPER  -> out.contains("Junos") || out.contains("JUNOS");
                    case ARISTA   -> out.contains("Arista") || out.contains("EOS");
                    default -> false;
                };
                if (!matches) {
                    Vendor detected = Vendor.detect(out);
                    if (detected == Vendor.UNKNOWN) continue;
                    matches = (detected == v);
                }

                if (matches) {
                    Vendor detected = Vendor.detect(out);
                    device.setVendor(detected != Vendor.UNKNOWN ? detected : v);
                    log.info("Vendor detectat via SSH probe paralel pe {}: {}", device.getManagementIp(), device.getVendor());
                    VendorAdapter.ParsedVersionInfo info = pr.adapter().parseVersionOutput(out);
                    if (info.hostname()     != null && device.getHostname()     == null) device.setHostname(info.hostname());
                    if (info.model()        != null) device.setModel(info.model());
                    if (info.osVersion()    != null) device.setOsVersion(info.osVersion());
                    if (info.serialNumber() != null) device.setSerialNumber(info.serialNumber());
                    return null; // succes
                }
            } catch (Exception e) {
                log.debug("SSH probe get() esuat: {}", e.getMessage());
            }
        }

        return "Vendor nedetectabil via SSH (probate MIKROTIK, ARISTA, JUNIPER). "
             + "Verifica credentialele SSH si SNMP community.";
    }

    /** Returneaza null daca SSH a reusit, sau mesajul de eroare daca a esuat (non-fatal). */
    private String pollViaSsh(Device device, String sshPassword) {
        try {
            VendorAdapter adapter = vendorAdapterFactory.getAdapter(device.getVendor());
            String cmd = adapter.getShowVersionCommand();
            log.debug("SSH [{}] catre {}: '{}'", device.getVendor(), device.getManagementIp(), cmd);

            String out = sshExecutor.executeCommand(
                    device.getManagementIp(), SSH_PORT,
                    device.getSshUsername(), sshPassword, cmd);

            log.debug("SSH output {}: {}",
                    device.getManagementIp(),
                    out.length() > 300 ? out.substring(0, 300) + "..." : out);

            VendorAdapter.ParsedVersionInfo info = adapter.parseVersionOutput(out);
            if (info.hostname() != null && device.getHostname() == null) device.setHostname(info.hostname());
            if (info.model()    != null) device.setModel(info.model());
            if (info.osVersion() != null) device.setOsVersion(info.osVersion());
            if (info.serialNumber() != null) device.setSerialNumber(info.serialNumber());

            // MikroTik: hostname vine din /system identity print (apel separat),
            // deoarece JSch exec nu interpreteaza \n ca separator de comenzi.
            // Facem apelul separat DOAR daca hostname-ul inca nu e setat (nici din SNMP sysName).
            if (device.getVendor() == Vendor.MIKROTIK && device.getHostname() == null) {
                try {
                    String identityOut = sshExecutor.executeCommand(
                            device.getManagementIp(), SSH_PORT,
                            device.getSshUsername(), sshPassword,
                            adapter.getShowHostnameCommand());
                    VendorAdapter.ParsedVersionInfo idInfo = adapter.parseVersionOutput(identityOut);
                    if (idInfo.hostname() != null) device.setHostname(idInfo.hostname());
                } catch (Exception e) {
                    log.debug("SSH identity esuat pe MikroTik {}: {}", device.getManagementIp(), e.getMessage());
                }
            }

            log.info("SSH OK {} [{}] -> hostname={} model={} version={}",
                    device.getManagementIp(), device.getVendor(),
                    device.getHostname(), device.getModel(), device.getOsVersion());
            return null;

        } catch (Exception e) {
            log.warn("SSH esuat pe {} [{}]: {}", device.getManagementIp(), device.getVendor(), e.getMessage());
            return e.getMessage();
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
            // Cauta link existent intre aceasta pereche de device-uri (in orice directie)
            Optional<Link> existingLink = linkRepository.findByLocalDevice(localDevice).stream()
                    .filter(l -> remoteDevice.equals(l.getRemoteDevice()))
                    .findFirst();
            if (existingLink.isEmpty()) {
                existingLink = linkRepository.findByLocalDevice(remoteDevice).stream()
                        .filter(l -> localDevice.equals(l.getRemoteDevice()))
                        .findFirst();
            }

            if (existingLink.isPresent()) {
                // Link existent — updatam interfetele DOAR daca noile date contin
                // un AE/bonding name mai bun decat ce era stocat (prioritate bonding)
                Link lnk = existingLink.get();
                boolean updated = false;
                if (isBetterIfName(neighbor.getLocalPortId(), lnk.getLocalInterfaceName())) {
                    lnk.setLocalInterfaceName(neighbor.getLocalPortId());
                    updated = true;
                }
                if (isBetterIfName(neighbor.getRemotePortId(), lnk.getRemoteInterfaceName())) {
                    lnk.setRemoteInterfaceName(neighbor.getRemotePortId());
                    updated = true;
                }
                if (updated) {
                    linkRepository.save(lnk);
                    log.debug("Link {}<->{} updatat cu interfete mai bune: local={} remote={}",
                            localDevice.getManagementIp(), remoteDevice.getManagementIp(),
                            lnk.getLocalInterfaceName(), lnk.getRemoteInterfaceName());
                }
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

    /**
     * Returneaza true daca 'candidate' e un nume de interfata mai bun decat 'current'.
     * Prioritate: AE/bond explicit > LACP/LAG description > interfata fizica > null.
     *
     * Ex: "ae0" > "LACP-1/2-AE0" > "ge-0/0/1" > null
     */
    private boolean isBetterIfName(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) return false;
        if (current == null || current.isBlank()) return true;
        return bondingScore(candidate) > bondingScore(current);
    }

    private int bondingScore(String name) {
        if (name == null) return 0;
        String lower = name.toLowerCase();
        // AE/bond direct → scor maxim
        if (lower.matches("ae\\d+.*") || lower.matches("bond\\d+.*") || lower.matches("po\\d+.*")) return 3;
        // Contine LACP/LAG → bun, dar nu ideal
        if (lower.contains("lacp") || lower.contains("lag")) return 2;
        // Interfata fizica simpla
        return 1;
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
