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

    // Lock pentru faza de scriere a topologiei (rezolvare vecin + creare link).
    // Partea lenta (SNMP/SSH) ramane in afara lock-ului; aici doar operatii DB rapide.
    // Elimina race-ul din BFS-ul paralel care altfel ar crea device-uri/link-uri duplicate
    // (doua thread-uri care vad acelasi vecin/aceeasi pereche in acelasi timp).
    private final Object topologyWriteLock = new Object();

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
            log.warn("Discovery deja in curs, ignoram request-ul nou");
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

            // Colapsam device-urile duplicate vazute prin mai multe subnet-uri
            // (acelasi device fizic, IP-uri de management diferite -> acelasi loopback/hostname).
            dedupeDevicesByIdentity();

            finishedAt = LocalDateTime.now().toString();
            // Numaram perechile unice de device-uri (dedup bidirectional, ca in GraphBuilder)
            long totalLinks = linkRepository.findAll().stream()
                    .filter(l -> l.getLocalDevice() != null && l.getRemoteDevice() != null)
                    .map(l -> {
                        long a = l.getLocalDevice().getId(), b = l.getRemoteDevice().getId();
                        return Math.min(a, b) + "-" + Math.max(a, b);
                    })
                    .distinct()
                    .count();
            // Numarul real de device-uri din topologie (dupa dedup), nu cate au fost procesate
            int totalDevices = (int) deviceRepository.count();
            if (stopRequested.get()) {
                log.info("Discovery oprit manual dupa {} device-uri", devicesProcessed.get());
                progressNotifier.notifyStopped(totalDevices, totalLinks);
            } else {
                progressNotifier.notifyCompleted(totalDevices, totalLinks);
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
                        // verificam stop la inceputul fiecarui task — nu doar intre niveluri
                        if (stopRequested.get()) return List.<Device>of();
                        Device device = deviceRepository.findById(deviceId).orElse(null);
                        if (device == null) return List.<Device>of();
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
                            if (n == null || n.getId() == null) continue;
                            if (visited.add(n.getId())
                                    && devicesProcessed.get() + nextLevel.size() < maxDevices) {
                                nextLevel.add(n.getId());
                            }
                        }
                    } catch (TimeoutException e) {
                        future.cancel(true); // eliberam thread-ul, nu il lasam sa ruleze
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

            // Pasul 1b: vendor prin sysObjectID (OID enterprise) — semnal robust, independent
            // de textul sysDescr. Functioneaza chiar daca sysDescr a dat timeout sau e gol.
            String sysObjectId = snmpCollector.getSysObjectId(device.getManagementIp(), snmpCommunity);
            if ((device.getVendor() == Vendor.UNKNOWN || device.getVendor() == null) && sysObjectId != null) {
                Vendor byOid = Vendor.detectFromSysObjectId(sysObjectId);
                if (byOid != Vendor.UNKNOWN) {
                    device.setVendor(byOid);
                    log.info("Vendor detectat pentru {} din sysObjectID ({}): {}",
                            device.getManagementIp(), sysObjectId, byOid);
                }
            }

            // hostname din SNMP — functioneaza pe ORICE vendor (OID standard sysName.0)
            String sysName = snmpCollector.getSysName(device.getManagementIp(), snmpCommunity);
            if (sysName != null && !sysName.isBlank()) {
                device.setHostname(sysName.trim());
            } else if (sysDescr == null) {
                // daca si sysName e null, SNMP e complet nefunctional pe acest device
                warnings.add("[SNMP] sysName: indisponibil");
            }

            // SNMP e considerat "viu" daca a raspuns la sysDescr, sysObjectID SAU sysName.
            // Asa interogam IF-MIB + LLDP si pe device-uri de alt vendor care nu
            // populeaza sysDescr asteptat dar raspund la restul OID-urilor standard.
            boolean snmpAlive = sysDescr != null || sysObjectId != null
                    || (sysName != null && !sysName.isBlank());

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
            if (snmpAlive) {
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
            if (snmpAlive) {
                try {
                    lldpNeighbors = snmpCollector.walkLldpNeighbors(device.getManagementIp(), snmpCommunity);
                } catch (Exception e) {
                    warnings.add("[SNMP] LLDP walk esuat: " + e.getMessage());
                    log.warn("LLDP walk esuat pe {}: {}", device.getManagementIp(), e.getMessage());
                }
            }

            log.info("LLDP: {} vecini pe {}", lldpNeighbors.size(), device.getManagementIp());

            for (SnmpCollector.LldpNeighbor neighbor : lldpNeighbors) {
                // Faza de scriere serializata: rezolvarea vecinului + crearea link-ului trebuie
                // sa fie atomice fata de celelalte thread-uri BFS, altfel doua thread-uri pot
                // crea acelasi device/link de doua ori (duplicate + link afisat dublu).
                synchronized (topologyWriteLock) {
                    Device neighborDevice = resolveOrCreateNeighbor(neighbor, device, sshPassword, snmpCommunity);
                    // guard: un device care se "vede" pe sine in LLDP nu trebuie sa creeze self-link
                    if (sameDev(neighborDevice, device)) continue;
                    createLink(device, neighbor, neighborDevice);
                    if (neighborDevice != null
                            && neighborDevice.getStatus() == DeviceStatus.DISCOVERED
                            && !neighborDevice.getManagementIp().startsWith("lldp:")) {
                        newNeighbors.add(neighborDevice);
                    }
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

        // Detectam loopback-ul (router-id stabil) — preferat ca identitate primara in UI
        // si folosit la deduplicarea device-urilor vazute prin mai multe subnet-uri.
        String loopback = detectLoopbackIp(entries);
        if (loopback != null) device.setLoopbackIp(loopback);
    }

    /**
     * Alege IP-ul de loopback dintr-un set de interfete.
     * Recunoaste lo / lo0 / loN / loopback / loopbackN (orice vendor), exclude 127.x.
     * Daca sunt mai multe, prefera lo0 / loopback0, altfel primul gasit.
     */
    private String detectLoopbackIp(List<SnmpCollector.InterfaceEntry> entries) {
        String best = null;
        for (SnmpCollector.InterfaceEntry e : entries) {
            String name = e.getName();
            String ip   = e.getIpAddress();
            if (name == null || ip == null) continue;
            String n = name.toLowerCase().trim();
            boolean isLoopback = n.matches("lo\\d*") || n.startsWith("loopback") || n.equals("lo");
            if (!isLoopback) continue;
            if (ip.startsWith("127.") || ip.equals("0.0.0.0")) continue;
            // prioritate pentru lo0 / loopback0
            if (n.equals("lo0") || n.equals("loopback0")) return ip;
            if (best == null) best = ip;
        }
        return best;
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
        String mgmtIp = neighbor.getRemoteManagementIp(); // poate fi null

        // 1. cauta dupa hostname exact sau management IP (din numele de sistem)
        Optional<Device> byHostname = deviceRepository.findFirstByHostnameIgnoreCase(remoteName);
        if (byHostname.isEmpty()) {
            byHostname = deviceRepository.findByManagementIp(remoteName);
        }
        if (byHostname.isPresent()) {
            enrichFromLldp(byHostname.get(), remoteName, mgmtIp);
            return byHostname.get();
        }

        // 2. cauta dupa IP-ul de management advertizat prin LLDP (lldpRemManAddr).
        //    Asa recuperam identitatea device-urilor care au esuat polling-ul (orfani UNKNOWN):
        //    le completam hostname-ul din LLDP -> devin deduplicabile.
        if (mgmtIp != null) {
            Optional<Device> byIp = deviceRepository.findByManagementIp(mgmtIp);
            if (byIp.isPresent()) {
                enrichFromLldp(byIp.get(), remoteName, mgmtIp);
                return byIp.get();
            }
        }

        // 3. cauta dupa chassis MAC in interfete
        if (neighbor.getRemoteChassisId() != null) {
            Optional<NetworkInterface> byMac =
                    interfaceRepository.findFirstByMacAddress(neighbor.getRemoteChassisId());
            if (byMac.isPresent()) {
                enrichFromLldp(byMac.get().getDevice(), remoteName, mgmtIp);
                return byMac.get().getDevice();
            }
        }

        // 4. device nou descoperit prin LLDP.
        //    Daca avem IP-ul lui de management (din LLDP) -> il cream cu IP-ul real, ca
        //    BFS-ul sa-l interogheze (status DISCOVERED). Altfel ramane placeholder "lldp:<hostname>".
        String newIp = (mgmtIp != null) ? mgmtIp : "lldp:" + remoteName;
        Optional<Device> existing = deviceRepository.findByManagementIp(newIp);
        if (existing.isPresent()) {
            enrichFromLldp(existing.get(), remoteName, mgmtIp);
            return existing.get();
        }

        log.info("Vecin LLDP nou: {} (mgmtIp={}) - cream device", remoteName, mgmtIp);
        Device created = Device.builder()
                .managementIp(newIp)
                .hostname(remoteName)
                .vendor(Vendor.UNKNOWN)
                .status(DeviceStatus.DISCOVERED)
                .seedDevice(false)
                .sshUsername(sourceDevice.getSshUsername())
                .sshPasswordEncrypted(sourceDevice.getSshPasswordEncrypted())
                .snmpCommunityEncrypted(sourceDevice.getSnmpCommunityEncrypted())
                .build();
        try {
            created = deviceRepository.save(created);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race in BFS paralel: alt thread a creat acelasi IP intre check si save.
            // Refolosim device-ul deja existent in loc sa esuam.
            return deviceRepository.findByManagementIp(newIp).orElseThrow(() -> e);
        }

        // notifica frontend-ul imediat de nodul nou
        progressNotifier.notifyNodeDiscovered(GraphBuilderService.toNode(created));
        return created;
    }

    /**
     * Completeaza un device existent cu informatii din LLDP (cand a fost gasit ca vecin).
     * In principal recupereaza hostname-ul orfanilor care au esuat polling-ul SNMP/SSH,
     * facandu-i deduplicabili. Nu suprascrie date deja existente.
     */
    private void enrichFromLldp(Device d, String remoteName, String mgmtIp) {
        if (d == null) return;
        boolean changed = false;
        if ((d.getHostname() == null || d.getHostname().isBlank())
                && remoteName != null && !remoteName.isBlank()
                && !remoteName.equalsIgnoreCase(d.getManagementIp())) {
            d.setHostname(remoteName.trim());
            changed = true;
        }
        if (changed) {
            deviceRepository.save(d);
            progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(d));
        }
    }

    /**
     * Creeaza sau actualizeaza un link, aplicand regula de fiabilitate a interfetelor:
     *
     *  - Capatul unui device se completeaza AUTORITAR doar din portul local LLDP al
     *    acelui device (cand device-ul a fost interogat). Portul local e de incredere
     *    fiindca device-ul isi cunoaste propriile interfete.
     *  - Capatul celuilalt device se completeaza din portul advertizat prin LLDP DOAR
     *    daca e un nume curat de interfata SI capatul e inca gol. Cand celalalt device
     *    e interogat la randul lui, capatul sau primeste valoarea autoritara.
     *  - Daca nu putem determina cu incredere → ramane gol (mai bine gol decat gresit).
     *
     * Deduplicare pe PERECHE de device-uri (bonding/LAG = un singur link logic).
     */
    private void createLink(Device localDevice, SnmpCollector.LldpNeighbor neighbor, Device remoteDevice) {
        String localIf  = neighbor.getLocalPortId();   // autoritar pt capatul localDevice (deja curatat sau null)
        String remoteIf = neighbor.getRemotePortId();  // advertizat pt capatul remoteDevice (curatat sau null)

        // ── Vecin nerezolvat (placeholder) ──────────────────────────────────
        if (remoteDevice == null) {
            Optional<Link> existing = linkRepository.findByLocalDevice(localDevice).stream()
                    .filter(l -> l.getRemoteDevice() == null
                              && neighbor.getRemoteSystemName() != null
                              && neighbor.getRemoteSystemName().equals(l.getRemoteSystemName()))
                    .findFirst();
            if (existing.isPresent()) {
                Link l = existing.get();
                l.setLocalInterfaceName(preferIfName(l.getLocalInterfaceName(), localIf)); // autoritar
                if (l.getRemoteInterfaceName() == null && remoteIf != null) l.setRemoteInterfaceName(remoteIf);
                linkRepository.save(l);
                return;
            }
            linkRepository.save(Link.builder()
                    .localDevice(localDevice).localInterfaceName(localIf)
                    .remoteDevice(null).remoteInterfaceName(remoteIf)
                    .remoteSystemName(neighbor.getRemoteSystemName())
                    .remoteChassisId(neighbor.getRemoteChassisId())
                    .source(Link.DiscoverySource.LLDP).build());
            return;
        }

        // ── Pereche cunoscuta — cautam link existent in orice orientare ─────
        Optional<Link> pair = findPairLink(localDevice, remoteDevice);
        if (pair.isPresent()) {
            Link l = pair.get();
            // capatul localDevice = autoritar (portul lui propriu)
            setEndInterface(l, localDevice, localIf, true);
            // capatul remoteDevice = advertizat, doar daca inca gol
            setEndInterface(l, remoteDevice, remoteIf, false);
            linkRepository.save(l);
            return;
        }

        // ── Link nou ────────────────────────────────────────────────────────
        Link link = linkRepository.save(Link.builder()
                .localDevice(localDevice).localInterfaceName(localIf)
                .remoteDevice(remoteDevice).remoteInterfaceName(remoteIf)
                .remoteSystemName(neighbor.getRemoteSystemName())
                .remoteChassisId(neighbor.getRemoteChassisId())
                .source(Link.DiscoverySource.LLDP).build());

        progressNotifier.notifyLinkDiscovered(
                TopologyGraphResponse.GraphEdge.builder()
                        .id("link-" + link.getId())
                        .source(String.valueOf(localDevice.getId()))
                        .target(String.valueOf(remoteDevice.getId()))
                        .sourceInterface(localIf)
                        .targetInterface(remoteIf)
                        .discoverySource("LLDP")
                        .build());
    }

    /** Compara doua device-uri dupa ID (sigur pentru entitati JPA). */
    private boolean sameDev(Device a, Device b) {
        return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
    }

    /** Gaseste link-ul intre perechea (a,b) in orice orientare. */
    private Optional<Link> findPairLink(Device a, Device b) {
        Optional<Link> x = linkRepository.findByLocalDevice(a).stream()
                .filter(l -> sameDev(b, l.getRemoteDevice())).findFirst();
        if (x.isPresent()) return x;
        return linkRepository.findByLocalDevice(b).stream()
                .filter(l -> sameDev(a, l.getRemoteDevice())).findFirst();
    }

    /**
     * Seteaza interfata pentru capatul lui `device` din link.
     * overwrite=true → autoritar (portul propriu al device-ului), inlocuieste mereu;
     * overwrite=false → advertizat, completeaza doar daca e gol.
     */
    private void setEndInterface(Link l, Device device, String ifName, boolean overwrite) {
        if (ifName == null) return;
        if (sameDev(device, l.getLocalDevice())) {
            if (overwrite) l.setLocalInterfaceName(preferIfName(l.getLocalInterfaceName(), ifName));
            else if (l.getLocalInterfaceName() == null) l.setLocalInterfaceName(ifName);
        } else if (sameDev(device, l.getRemoteDevice())) {
            if (overwrite) l.setRemoteInterfaceName(preferIfName(l.getRemoteInterfaceName(), ifName));
            else if (l.getRemoteInterfaceName() == null) l.setRemoteInterfaceName(ifName);
        }
    }

    /**
     * Alege numele preferat pentru un capat autoritar (cazul bonding cu mai multe
     * porturi fizice): preferam un nume de agregat (ae/bond/po) daca exista, altfel
     * pastram alegerea deterministă (cel mai mic lexicografic) ca sa nu "pâlpâie".
     */
    private String preferIfName(String current, String candidate) {
        if (candidate == null) return current;
        if (current == null)   return candidate;
        int cc = aggScore(current), sc = aggScore(candidate);
        if (sc != cc) return sc > cc ? candidate : current;
        return candidate.compareToIgnoreCase(current) < 0 ? candidate : current;
    }

    private int aggScore(String n) {
        return n != null && n.toLowerCase().matches("(ae|bond|po|port-channel).*") ? 1 : 0;
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

                // Muta link-urile de la placeholder la device-ul real (null-safe)
                linkRepository.findByLocalDevice(placeholder).forEach(link -> {
                    if (sameDev(link.getRemoteDevice(), real)) {
                        linkRepository.delete(link); // ar deveni self-loop
                    } else {
                        link.setLocalDevice(real);
                        linkRepository.save(link);
                    }
                });

                linkRepository.findByRemoteDevice(placeholder).forEach(link -> {
                    if (sameDev(link.getLocalDevice(), real)) {
                        linkRepository.delete(link); // ar deveni self-loop
                    } else {
                        link.setRemoteDevice(real);
                        linkRepository.save(link);
                    }
                });

                // Sterge placeholder-ul si interfetele lui
                interfaceRepository.findByDevice(placeholder).forEach(interfaceRepository::delete);
                deviceRepository.delete(placeholder);

                // Notifica frontend: nodul placeholder a fost inlocuit cu cel real
                progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(real));
            });
        }

        // Dupa mutarea link-urilor pot aparea duplicate pe aceeasi pereche — le colapsam
        dedupeAllLinks();
    }

    /**
     * Colapseaza device-urile duplicate care reprezinta acelasi device fizic vazut
     * prin mai multe subnet-uri de management (ex: routerul raspunde la SSH pe
     * 192.168.1.1 SI pe 10.0.0.1 -> doua randuri Device pentru acelasi device).
     *
     * Identitate stabila, in ordinea increderii:
     *   1. loopbackIp (router-id, nu depinde de subnet, unic per device)
     *   2. hostname (lowercase, unic per device intr-o retea corecta)
     *
     * NU folosim serialNumber: in laboratoare virtualizate (EVE-NG) imaginile clonate
     * vMX/vQFX raporteaza frecvent acelasi serial, ceea ce ar contopi GRESIT doua
     * device-uri diferite (supra-merge — mai grav decat un duplicat ramas).
     *
     * Pentru fiecare grup de duplicate pastram un "master", ii mutam link-urile si
     * interfetele, alegem loopback-ul ca identitate, apoi stergem duplicatele.
     */
    private void dedupeDevicesByIdentity() {
        // Excludem placeholder-urile nerezolvate (lldp:*) — ele se trateaza separat
        List<Device> reals = deviceRepository.findAll().stream()
                .filter(d -> d.getManagementIp() != null && !d.getManagementIp().startsWith("lldp:"))
                .filter(d -> d.getId() != null)
                .toList();
        if (reals.size() < 2) return;

        // Union-find: doua device-uri se contopesc daca impart ORICE identitate
        // (loopback / serial / hostname). Astfel acoperim si cazul in care un IP a
        // detectat loopback-ul iar celalalt nu, dar ambele au acelasi hostname.
        Map<Long, Long> parent = new HashMap<>();
        for (Device d : reals) parent.put(d.getId(), d.getId());

        Map<String, Long> firstWithKey = new HashMap<>();
        for (Device d : reals) {
            for (String key : identityKeys(d)) {
                Long other = firstWithKey.putIfAbsent(key, d.getId());
                if (other != null) union(parent, other, d.getId());
            }
        }

        // Grupam pe radacina
        Map<Long, Device> byId = new HashMap<>();
        for (Device d : reals) byId.put(d.getId(), d);
        Map<Long, List<Device>> clusters = new HashMap<>();
        for (Device d : reals) {
            clusters.computeIfAbsent(find(parent, d.getId()), x -> new ArrayList<>()).add(d);
        }

        int merged = 0;
        for (List<Device> cluster : clusters.values()) {
            if (cluster.size() < 2) continue;
            Device master = pickMaster(cluster);
            for (Device d : cluster) {
                if (!sameDev(d, master)) { mergeDeviceInto(master, d); merged++; }
            }
        }

        if (merged > 0) {
            log.info("Dedup device-uri: colapsate {} duplicate (multi-subnet)", merged);
            dedupeAllLinks(); // dupa mutarea link-urilor pot aparea perechi duplicate
        }
    }

    /** Identitatile stabile ale unui device (loopback + hostname). Poate fi goala. */
    private Set<String> identityKeys(Device d) {
        Set<String> keys = new LinkedHashSet<>();
        if (d.getLoopbackIp() != null && !d.getLoopbackIp().isBlank()) keys.add("lo:" + d.getLoopbackIp().trim());
        if (d.getHostname()   != null && !d.getHostname().isBlank())   keys.add("hn:" + d.getHostname().trim().toLowerCase());
        return keys;
    }

    /** Master-ul unui cluster: preferam device-ul cu loopback (mai complet), apoi cel mai mic id. */
    private Device pickMaster(List<Device> cluster) {
        return cluster.stream()
                .min(Comparator
                        .comparing((Device d) -> d.getLoopbackIp() == null) // false (are loopback) inainte
                        .thenComparing(Device::getId))
                .orElse(cluster.get(0));
    }

    private Long find(Map<Long, Long> parent, Long x) {
        Long root = x;
        while (!root.equals(parent.get(root))) root = parent.get(root);
        // compresie de cale
        while (!x.equals(root)) { Long next = parent.get(x); parent.put(x, root); x = next; }
        return root;
    }

    private void union(Map<Long, Long> parent, Long a, Long b) {
        Long ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /** Muta link-urile si interfetele de la dup la master, completeaza campurile lipsa, sterge dup. */
    private void mergeDeviceInto(Device master, Device dup) {
        if (sameDev(master, dup)) return;
        log.info("Merge device {} ({}) -> {} ({})",
                dup.getManagementIp(), dup.getHostname(),
                master.getManagementIp(), master.getHostname());

        // mutam link-urile (null-safe pe self-loop)
        linkRepository.findByLocalDevice(dup).forEach(link -> {
            if (sameDev(link.getRemoteDevice(), master)) linkRepository.delete(link);
            else { link.setLocalDevice(master); linkRepository.save(link); }
        });
        linkRepository.findByRemoteDevice(dup).forEach(link -> {
            if (sameDev(link.getLocalDevice(), master)) linkRepository.delete(link);
            else { link.setRemoteDevice(master); linkRepository.save(link); }
        });

        // mutam interfetele pe care master nu le are deja (dupa nume)
        for (NetworkInterface iface : interfaceRepository.findByDevice(dup)) {
            if (interfaceRepository.findFirstByDeviceAndName(master, iface.getName()).isPresent()) {
                interfaceRepository.delete(iface);
            } else {
                iface.setDevice(master);
                interfaceRepository.save(iface);
            }
        }

        // completam campurile lipsa pe master din dup
        if (master.getLoopbackIp()    == null && dup.getLoopbackIp()    != null) master.setLoopbackIp(dup.getLoopbackIp());
        if (master.getHostname()      == null && dup.getHostname()      != null) master.setHostname(dup.getHostname());
        if (master.getModel()         == null && dup.getModel()         != null) master.setModel(dup.getModel());
        if (master.getOsVersion()     == null && dup.getOsVersion()     != null) master.setOsVersion(dup.getOsVersion());
        if (master.getSerialNumber()  == null && dup.getSerialNumber()  != null) master.setSerialNumber(dup.getSerialNumber());
        if (master.getVendor() == null || master.getVendor() == Vendor.UNKNOWN) {
            if (dup.getVendor() != null && dup.getVendor() != Vendor.UNKNOWN) master.setVendor(dup.getVendor());
        }
        deviceRepository.save(master);

        deviceRepository.delete(dup);
        progressNotifier.notifyNodeUpdated(GraphBuilderService.toNode(master));
    }

    /**
     * Colapseaza link-urile duplicate pe aceeasi pereche de device-uri (in orice orientare),
     * fuzionand numele de interfata pe fiecare capat. Sterge si self-loop-urile.
     */
    private void dedupeAllLinks() {
        List<Link> all = linkRepository.findAll();
        Map<String, Link> keep = new HashMap<>();
        List<Link> toDelete = new ArrayList<>();

        for (Link l : all) {
            if (l.getLocalDevice() == null || l.getRemoteDevice() == null) continue;
            Long a = l.getLocalDevice().getId();
            Long b = l.getRemoteDevice().getId();
            if (a == null || b == null) continue;
            if (a.equals(b)) { toDelete.add(l); continue; } // self-loop

            String key = Math.min(a, b) + "-" + Math.max(a, b);
            Link master = keep.get(key);
            if (master == null) {
                keep.put(key, l);
            } else {
                // fuzionam interfetele lui l in master (completam capetele goale)
                fillEnd(master, l.getLocalDevice(),  l.getLocalInterfaceName());
                fillEnd(master, l.getRemoteDevice(), l.getRemoteInterfaceName());
                linkRepository.save(master);
                toDelete.add(l);
            }
        }
        if (!toDelete.isEmpty()) {
            linkRepository.deleteAll(toDelete);
            log.info("Dedup link-uri: colapsate {} duplicate", toDelete.size());
        }
    }

    /** Completeaza capatul lui `dev` din `link` cu `ifName` doar daca e gol. */
    private void fillEnd(Link link, Device dev, String ifName) {
        if (dev == null || ifName == null) return;
        if (sameDev(dev, link.getLocalDevice())  && link.getLocalInterfaceName()  == null) link.setLocalInterfaceName(ifName);
        else if (sameDev(dev, link.getRemoteDevice()) && link.getRemoteInterfaceName() == null) link.setRemoteInterfaceName(ifName);
    }
}
