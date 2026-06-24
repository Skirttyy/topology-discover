package com.topo.discovery.service;

import com.topo.discovery.collector.SnmpCollector;
import com.topo.discovery.collector.SshCommandExecutor;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motorul central de discovery: pornind de la un (sau mai multe) seed
 * device(s), face BFS (Breadth-First Search) pe graful retelei.
 *
 * Algoritm (pe scurt, pt documentatia tezei):
 *   1. coada <- [seed devices]
 *   2. cat timp coada nu e goala si nu am depasit max_devices/max_depth:
 *        device <- coada.pop()
 *        daca device.status != ACTIVE:
 *            bootstrap (activeaza SNMP+LLDP daca lipsesc)
 *            poll (SSH show version/hostname + SNMP walk interfete)
 *        vecini <- LLDP walk(device) + ARP walk(device) [fallback]
 *        pentru fiecare vecin:
 *            daca vecinul nu e cunoscut -> creeaza Device nou (status DISCOVERED), adauga in coada
 *            creeaza/actualizeaza Link intre device si vecin
 *
 * E un BFS clasic pe un graf necunoscut a priori, descoperit incremental -
 * exact genul de aplicatie practica a teoriei grafurilor care merge bine
 * intr-o teza de an la Informatica Aplicata.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryEngineService {

    private final DeviceRepository deviceRepository;
    private final NetworkInterfaceRepository interfaceRepository;
    private final LinkRepository linkRepository;
    private final DeviceService deviceService;
    private final BootstrapConfigService bootstrapConfigService;
    private final SshCommandExecutor sshExecutor;
    private final SnmpCollector snmpCollector;
    private final VendorAdapterFactory vendorAdapterFactory;
    private final DiscoveryProgressNotifier progressNotifier;

    @Value("${discovery.bfs.max-depth}")
    private int maxDepth;

    @Value("${discovery.bfs.max-devices}")
    private int maxDevices;

    private static final int SSH_PORT = 22;

    // status global simplu al ultimei rulari, expus prin API pentru frontend (polling de progres)
    private final Map<String, Object> lastRunStatus = new ConcurrentHashMap<>();

    /**
     * Porneste discovery-ul asincron de la o lista de device-uri seed.
     * Ruleaza pe alt thread (vezi @EnableAsync din DiscoveryApplication)
     * ca sa nu blocheze request-ul HTTP - poate dura minute intregi pe un
     * lab mare.
     */
    @Async
    public void runDiscoveryAsync(List<Long> seedDeviceIds) {
        lastRunStatus.put("running", true);
        lastRunStatus.put("startedAt", LocalDateTime.now().toString());
        lastRunStatus.put("devicesProcessed", 0);
        lastRunStatus.put("error", null);

        try {
            runBfs(seedDeviceIds);
            lastRunStatus.put("running", false);
            lastRunStatus.put("finishedAt", LocalDateTime.now().toString());
            progressNotifier.notifyCompleted();
        } catch (Exception e) {
            log.error("Discovery BFS a esuat: {}", e.getMessage(), e);
            lastRunStatus.put("running", false);
            lastRunStatus.put("error", e.getMessage());
            progressNotifier.notifyError(e.getMessage());
        }
    }

    public Map<String, Object> getLastRunStatus() {
        return new HashMap<>(lastRunStatus);
    }

    private void runBfs(List<Long> seedDeviceIds) {
        Queue<Long> queue = new LinkedList<>(seedDeviceIds);
        Set<Long> visited = new HashSet<>();
        Map<Long, Integer> depthMap = new HashMap<>();
        seedDeviceIds.forEach(id -> depthMap.put(id, 0));

        int processedCount = 0;

        while (!queue.isEmpty() && processedCount < maxDevices) {
            Long deviceId = queue.poll();
            if (visited.contains(deviceId)) {
                continue;
            }
            visited.add(deviceId);

            int depth = depthMap.getOrDefault(deviceId, 0);
            if (depth > maxDepth) {
                log.info("Adancime maxima ({}) atinsa, opresc explorarea pe ramura asta", maxDepth);
                continue;
            }

            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null) continue;

            log.info("BFS: procesez device {} (depth={})", device.getManagementIp(), depth);
            progressNotifier.notifyDeviceProcessing(device.getManagementIp());

            List<Device> newNeighbors = processDevice(device);

            for (Device neighbor : newNeighbors) {
                if (!visited.contains(neighbor.getId())) {
                    queue.add(neighbor.getId());
                    depthMap.put(neighbor.getId(), depth + 1);
                }
            }

            processedCount++;
            lastRunStatus.put("devicesProcessed", processedCount);
        }

        log.info("BFS finalizat. Total device-uri procesate: {}", processedCount);
    }

    /**
     * Proceseaza un singur device: bootstrap (daca e nevoie), polling
     * (hostname/version/interfete), apoi descopera vecinii (LLDP + ARP).
     *
     * @return lista de device-uri noi descoperite (pentru a fi adaugate in coada BFS)
     */
    @Transactional
    public List<Device> processDevice(Device device) {
        List<Device> newlyDiscovered = new ArrayList<>();
        String sshPassword = deviceService.decryptSshPassword(device);
        String snmpCommunity = deviceService.decryptSnmpCommunity(device);

        try {
            // Pasul 1: bootstrap config (activeaza SNMP+LLDP daca lipsesc) - idempotent
            device.setStatus(DeviceStatus.CONFIGURING);
            deviceRepository.save(device);
            boolean bootstrapOk = bootstrapConfigService.bootstrap(device, sshPassword, snmpCommunity);
            if (!bootstrapOk) {
                device.setStatus(DeviceStatus.ERROR);
                deviceRepository.save(device);
                return newlyDiscovered;
            }

            // Pasul 2: polling de bază (hostname, model, OS version) prin SSH
            device.setStatus(DeviceStatus.POLLING);
            pollBasicInfo(device, sshPassword);

            // Pasul 3: polling interfete prin SNMP
            pollInterfaces(device, snmpCommunity);

            // Pasul 4: descoperire vecini - LLDP intai (sursa principala)
            List<SnmpCollector.LldpNeighbor> lldpNeighbors = snmpCollector.walkLldpNeighbors(
                    device.getManagementIp(), snmpCommunity);

            for (SnmpCollector.LldpNeighbor neighbor : lldpNeighbors) {
                Device neighborDevice = resolveOrCreateNeighbor(neighbor, device, sshPassword, snmpCommunity);
                if (neighborDevice != null && neighborDevice.getStatus() == DeviceStatus.DISCOVERED) {
                    newlyDiscovered.add(neighborDevice);
                }
                createOrUpdateLink(device, neighbor, neighborDevice);
            }

            // Pasul 5: fallback ARP/MAC - completeaza date L3 (nu creeaza device-uri noi,
            // doar imbogateste interfetele cu IP-uri daca lipsesc)
            enrichWithArpData(device, snmpCommunity);

            device.setStatus(DeviceStatus.ACTIVE);
            device.setLastPolledAt(LocalDateTime.now());
            device.setLastError(null);
            deviceRepository.save(device);

        } catch (Exception e) {
            log.error("Eroare la procesarea device-ului {}: {}", device.getManagementIp(), e.getMessage());
            device.setStatus(DeviceStatus.ERROR);
            device.setLastError(e.getMessage());
            deviceRepository.save(device);
        }

        return newlyDiscovered;
    }

    private void pollBasicInfo(Device device, String sshPassword) {
        VendorAdapter adapter = vendorAdapterFactory.getAdapter(device.getVendor());

        String hostnameOutput = sshExecutor.executeCommand(
                device.getManagementIp(), SSH_PORT, device.getSshUsername(), sshPassword,
                adapter.getShowHostnameCommand());
        device.setHostname(extractHostname(hostnameOutput, device.getManagementIp()));

        String versionOutput = sshExecutor.executeCommand(
                device.getManagementIp(), SSH_PORT, device.getSshUsername(), sshPassword,
                adapter.getShowVersionCommand());
        VendorAdapter.ParsedVersionInfo versionInfo = adapter.parseVersionOutput(versionOutput);
        device.setModel(versionInfo.model());
        device.setOsVersion(versionInfo.osVersion());
        if (versionInfo.serialNumber() != null) {
            device.setSerialNumber(versionInfo.serialNumber());
        }

        deviceRepository.save(device);
    }

    private String extractHostname(String rawOutput, String fallbackIp) {
        if (rawOutput == null || rawOutput.isBlank()) return fallbackIp;
        // incearca sa extraga un singur token rezonabil din output; fallback la IP daca esueaza
        String trimmed = rawOutput.trim().replaceAll("(?i)host-?name", "").replaceAll("[;\"]", "").trim();
        String[] lines = trimmed.split("\\R");
        for (String line : lines) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && !candidate.startsWith("%") && !candidate.startsWith("set")) {
                return candidate.replaceAll("^set\\s+system\\s+host-name\\s+", "");
            }
        }
        return fallbackIp;
    }

    private void pollInterfaces(Device device, String snmpCommunity) {
        List<SnmpCollector.InterfaceEntry> entries = snmpCollector.walkInterfaces(
                device.getManagementIp(), snmpCommunity);

        for (SnmpCollector.InterfaceEntry entry : entries) {
            if (entry.getName() == null) continue;

            NetworkInterface iface = interfaceRepository.findByDeviceAndName(device, entry.getName())
                    .orElse(NetworkInterface.builder().device(device).name(entry.getName()).build());

            iface.setMacAddress(entry.getMacAddress());
            iface.setAdminStatus(entry.getAdminStatus());
            iface.setOperStatus(entry.getOperStatus());
            if (entry.getSpeedBps() != null) {
                iface.setSpeedMbps(entry.getSpeedBps() / 1_000_000);
            }

            interfaceRepository.save(iface);
        }
    }

    /**
     * Pentru un vecin LLDP, incearca sa-l lege de un Device existent (cautat
     * dupa hostname). Daca nu exista, NU cream automat un Device fara IP
     * valid (LLDP-MIB standard nu garanteaza IP-ul de management al
     * vecinului in tabelele de baza folosite aici) - legatura ramane
     * inregistrata cu metadate (hostname/chassis remote) pentru a fi
     * rezolvata manual sau printr-un scan de subnet ulterior.
     *
     * Nota pt teza: aceasta e o limitare cunoscuta documentata, cu mentiunea
     * ca o extensie ar putea interoga si lldpRemManAddrTable pentru IP-ul
     * de management al vecinului direct din MIB.
     */
    private Device resolveOrCreateNeighbor(SnmpCollector.LldpNeighbor neighbor, Device sourceDevice,
                                             String sshPassword, String snmpCommunity) {
        if (neighbor.getRemoteSystemName() == null) {
            return null;
        }

        Optional<Device> existing = deviceRepository.findAll().stream()
                .filter(d -> neighbor.getRemoteSystemName().equalsIgnoreCase(d.getHostname()))
                .findFirst();

        if (existing.isPresent()) {
            return existing.get();
        }

        log.info("Vecin LLDP nou descoperit: {} (chassis={}), fara IP rezolvat inca",
                neighbor.getRemoteSystemName(), neighbor.getRemoteChassisId());

        return null;
    }

    private void createOrUpdateLink(Device localDevice, SnmpCollector.LldpNeighbor neighbor, Device remoteDevice) {
        Link link = Link.builder()
                .localDevice(localDevice)
                .localInterfaceName(neighbor.getRemotePortId())
                .remoteDevice(remoteDevice)
                .remoteInterfaceName(neighbor.getRemotePortId())
                .remoteSystemName(neighbor.getRemoteSystemName())
                .remoteChassisId(neighbor.getRemoteChassisId())
                .source(Link.DiscoverySource.LLDP)
                .build();
        linkRepository.save(link);
    }

    /**
     * Fallback L3: pentru fiecare IP gasit in ARP table, daca corespunde
     * unui Device deja cunoscut (prin MAC matching pe interfetele lui),
     * completam informatia. Util cand LLDP nu da rezultate complete.
     */
    private void enrichWithArpData(Device device, String snmpCommunity) {
        List<SnmpCollector.ArpEntry> arpEntries = snmpCollector.walkArpTable(
                device.getManagementIp(), snmpCommunity);

        for (SnmpCollector.ArpEntry arpEntry : arpEntries) {
            interfaceRepository.findByMacAddress(arpEntry.getMacAddress()).ifPresent(iface -> {
                if (iface.getIpAddress() == null) {
                    iface.setIpAddress(arpEntry.getIpAddress());
                    interfaceRepository.save(iface);
                }
            });
        }
    }
}
