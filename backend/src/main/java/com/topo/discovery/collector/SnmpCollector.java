package com.topo.discovery.collector;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Colecteaza date prin SNMP v2c folosind MIB-uri standard.
 *
 * OID-uri folosite:
 * - SNMPv2-MIB::sysDescr        -> detectie vendor (orice device cu SNMP)
 * - SNMPv2-MIB::sysName         -> hostname
 * - LLDP-MIB::lldpRemSysName    -> vecini LLDP (hostname remote)
 * - LLDP-MIB::lldpRemPortId     -> portul remote al vecinului
 * - LLDP-MIB::lldpLocPortId     -> portul local prin care e vazut vecinul
 * - LLDP-MIB::lldpRemChassisId  -> chassis ID al vecinului (MAC)
 * - LLDP-MIB::lldpRemManAddr    -> IP management al vecinului (gold standard!)
 * - IP-MIB::ipNetToMediaPhysAddress -> ARP table
 * - IF-MIB                       -> interfete
 */
@Component
@Slf4j
public class SnmpCollector {

    @Value("${discovery.snmp.timeout-ms}")
    private int timeoutMs;

    @Value("${discovery.snmp.retries}")
    private int retries;

    // --- System MIB ---
    private static final String OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0";
    private static final String OID_SYS_NAME  = "1.3.6.1.2.1.1.5.0";

    // --- LLDP-MIB ---
    private static final String OID_LLDP_REM_SYS_NAME   = "1.0.8802.1.1.2.1.4.1.1.9";
    private static final String OID_LLDP_REM_PORT_ID    = "1.0.8802.1.1.2.1.4.1.1.7";
    private static final String OID_LLDP_REM_PORT_DESCR = "1.0.8802.1.1.2.1.4.1.1.8";
    private static final String OID_LLDP_REM_CHASSIS_ID = "1.0.8802.1.1.2.1.4.1.1.5";
    private static final String OID_LLDP_LOC_PORT_ID    = "1.0.8802.1.1.2.1.3.7.1.3";
    // lldpRemManAddrTable - contine IP-ul de management al vecinului
    private static final String OID_LLDP_REM_MAN_ADDR   = "1.0.8802.1.1.2.1.4.2.1.3";

    // --- ARP ---
    private static final String OID_ARP_PHYS = "1.3.6.1.2.1.4.22.1.2";

    // --- IF-MIB ---
    private static final String OID_IF_DESCR        = "1.3.6.1.2.1.2.2.1.2";
    private static final String OID_IF_PHYS_ADDR    = "1.3.6.1.2.1.2.2.1.6";
    private static final String OID_IF_ADMIN_STATUS = "1.3.6.1.2.1.2.2.1.7";
    private static final String OID_IF_OPER_STATUS  = "1.3.6.1.2.1.2.2.1.8";
    private static final String OID_IF_SPEED        = "1.3.6.1.2.1.2.2.1.5";
    private static final String OID_IF_ALIAS        = "1.3.6.1.2.1.31.1.1.1.18";

    // --- IP addresses ---
    private static final String OID_IP_ADDR_IFINDEX  = "1.3.6.1.2.1.4.20.1.2";
    private static final String OID_IP_ADDR_NETMASK  = "1.3.6.1.2.1.4.20.1.3";

    /**
     * Obtine sysDescr - folosit pentru detectia vendor-ului.
     * Functioneaza pe ORICE device cu SNMP activ (Juniper, Arista, Mikrotik, etc.)
     */
    public String getSysDescr(String host, String community) {
        try {
            return getScalar(host, community, OID_SYS_DESCR);
        } catch (Exception e) {
            log.warn("sysDescr esuat pentru {}: {}", host, e.getMessage());
            return null;
        }
    }

    public String getSysName(String host, String community) {
        try {
            return getScalar(host, community, OID_SYS_NAME);
        } catch (Exception e) {
            log.warn("sysName esuat pentru {}: {}", host, e.getMessage());
            return null;
        }
    }

    /**
     * LLDP neighbors walk - sursa principala pentru link-urile topologiei.
     * Returneaza lista de vecini cu portul local si remote.
     */
    public List<LldpNeighbor> walkLldpNeighbors(String host, String community) {
        List<LldpNeighbor> neighbors = new ArrayList<>();
        try {
            // Walk-urile LLDP sunt independente — le rulam in paralel
            int timeoutSec = (timeoutMs * 2) / 1000 + 5;
            CompletableFuture<List<SnmpEntry>> sysNameF   = walkAsync(host, community, OID_LLDP_REM_SYS_NAME);
            CompletableFuture<List<SnmpEntry>> portIdF    = walkAsync(host, community, OID_LLDP_REM_PORT_ID);
            CompletableFuture<List<SnmpEntry>> portDescrF = walkAsync(host, community, OID_LLDP_REM_PORT_DESCR);
            CompletableFuture<List<SnmpEntry>> chassisF   = walkAsync(host, community, OID_LLDP_REM_CHASSIS_ID);
            CompletableFuture<List<SnmpEntry>> locPortF   = walkAsync(host, community, OID_LLDP_LOC_PORT_ID);

            // safeGet gestioneaza InterruptedException, TimeoutException, ExecutionException
            List<SnmpEntry> sysNames   = safeGet(sysNameF,   timeoutSec);
            List<SnmpEntry> portIds    = safeGet(portIdF,     timeoutSec);
            List<SnmpEntry> portDescrs = safeGet(portDescrF,  timeoutSec);
            List<SnmpEntry> chassisIds = safeGet(chassisF,    timeoutSec);
            List<SnmpEntry> locPorts   = safeGet(locPortF,    timeoutSec);

            // indexul LLDP e: <timeMark>.<localPortNum>.<remoteIndex>
            // ne intereseaza localPortNum ca sa stim prin ce port local vedem vecinul
            for (SnmpEntry sysNameEntry : sysNames) {
                String suffix = stripPrefix(sysNameEntry.oid(), OID_LLDP_REM_SYS_NAME);
                // suffix format: <timeMark>.<localPortNum>.<remoteIndex>
                String[] parts = suffix.split("\\.");
                String localPortNum = parts.length >= 2 ? parts[1] : null;

                String remotePortId    = findByOidSuffix(portIds, OID_LLDP_REM_PORT_ID, suffix);
                String remotePortDescr = findByOidSuffix(portDescrs, OID_LLDP_REM_PORT_DESCR, suffix);
                String chassisId       = findByOidSuffix(chassisIds, OID_LLDP_REM_CHASSIS_ID, suffix);

                // gasim portul local dupa localPortNum
                String localPortId = null;
                if (localPortNum != null) {
                    for (SnmpEntry lp : locPorts) {
                        if (lp.oid().endsWith("." + localPortNum)) {
                            localPortId = lp.value();
                            break;
                        }
                    }
                }

                String remoteName = sysNameEntry.value();
                // prefer portDescr ca e mai lizibil (ex: "ge-0/0/1" in loc de MAC sau index numeric)
                String remotePort = (remotePortDescr != null && !remotePortDescr.isBlank())
                        ? remotePortDescr : remotePortId;

                // filtram valorile pur numerice (ifIndex brut) — nu sunt utile ca nume de interfata
                remotePort  = isUsefulPortName(remotePort)  ? remotePort  : null;
                localPortId = isUsefulPortName(localPortId) ? localPortId : null;

                if (remoteName != null && !remoteName.isBlank()) {
                    neighbors.add(LldpNeighbor.builder()
                            .remoteSystemName(remoteName.trim())
                            .remotePortId(remotePort)
                            .remoteChassisId(formatMac(chassisId))
                            .localPortId(localPortId)
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("LLDP walk esuat pentru {}: {}", host, e.getMessage());
        }
        log.debug("LLDP walk {}: {} vecini gasiti", host, neighbors.size());
        return neighbors;
    }

    /**
     * ARP table walk - fallback L3, mapeaza IP->MAC.
     */
    public List<ArpEntry> walkArpTable(String host, String community) {
        List<ArpEntry> entries = new ArrayList<>();
        try {
            List<SnmpEntry> macEntries = walk(host, community, OID_ARP_PHYS);
            for (SnmpEntry entry : macEntries) {
                // OID: ...22.1.2.<ifIndex>.<ip1>.<ip2>.<ip3>.<ip4>
                String suffix = stripPrefix(entry.oid(), OID_ARP_PHYS);
                String[] parts = suffix.split("\\.");
                if (parts.length >= 5) {
                    String ip = parts[parts.length - 4] + "." + parts[parts.length - 3]
                            + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
                    entries.add(ArpEntry.builder()
                            .ipAddress(ip)
                            .macAddress(formatMac(entry.value()))
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("ARP walk esuat pentru {}: {}", host, e.getMessage());
        }
        return entries;
    }

    /**
     * IF-MIB walk - interfete cu status, MAC, viteza.
     */
    public List<InterfaceEntry> walkInterfaces(String host, String community) {
        List<InterfaceEntry> result = new ArrayList<>();
        try {
            int timeoutSec = (timeoutMs * 2) / 1000 + 5;

            // Lansam toate walk-urile IF-MIB in paralel
            CompletableFuture<List<SnmpEntry>> descrF  = walkAsync(host, community, OID_IF_DESCR);
            CompletableFuture<List<SnmpEntry>> macF    = walkAsync(host, community, OID_IF_PHYS_ADDR);
            CompletableFuture<List<SnmpEntry>> adminF  = walkAsync(host, community, OID_IF_ADMIN_STATUS);
            CompletableFuture<List<SnmpEntry>> operF   = walkAsync(host, community, OID_IF_OPER_STATUS);
            CompletableFuture<List<SnmpEntry>> speedF  = walkAsync(host, community, OID_IF_SPEED);
            CompletableFuture<List<SnmpEntry>> aliasF  = walkAsync(host, community, OID_IF_ALIAS);
            CompletableFuture<List<SnmpEntry>> ipIdxF  = walkAsync(host, community, OID_IP_ADDR_IFINDEX);
            CompletableFuture<List<SnmpEntry>> ipMaskF = walkAsync(host, community, OID_IP_ADDR_NETMASK);

            // safeGet nu arunca niciodata exceptie (gestioneaza InterruptedException, Timeout, etc.)
            List<SnmpEntry> descrs   = safeGet(descrF,  timeoutSec);
            List<SnmpEntry> macs     = safeGet(macF,    timeoutSec);
            List<SnmpEntry> adminSts = safeGet(adminF,  timeoutSec);
            List<SnmpEntry> operSts  = safeGet(operF,   timeoutSec);
            List<SnmpEntry> speeds   = safeGet(speedF,  timeoutSec);
            List<SnmpEntry> aliases  = safeGet(aliasF,  timeoutSec);
            List<SnmpEntry> ipIfIdx  = safeGet(ipIdxF,  timeoutSec);
            List<SnmpEntry> ipMasks  = safeGet(ipMaskF, timeoutSec);

            if (descrs.isEmpty()) {
                log.debug("IF-MIB walk: nicio interfata returnata de {}", host);
                return result;
            }

            // IP addresses per ifIndex
            Map<String, String> ifIndexToIp      = new HashMap<>();
            Map<String, String> ifIndexToNetmask = new HashMap<>();
            for (SnmpEntry e : ipIfIdx) {
                ifIndexToIp.put(e.value(), stripPrefix(e.oid(), OID_IP_ADDR_IFINDEX));
            }
            for (SnmpEntry e : ipMasks) {
                ifIndexToNetmask.put(stripPrefix(e.oid(), OID_IP_ADDR_NETMASK), e.value());
            }

            for (SnmpEntry descr : descrs) {
                String ifIndex = stripPrefix(descr.oid(), OID_IF_DESCR);
                String mac     = findByIndex(macs, OID_IF_PHYS_ADDR, ifIndex);
                String admin   = mapStatus(findByIndex(adminSts, OID_IF_ADMIN_STATUS, ifIndex));
                String oper    = mapStatus(findByIndex(operSts, OID_IF_OPER_STATUS, ifIndex));
                String speedRaw = findByIndex(speeds, OID_IF_SPEED, ifIndex);
                String alias   = findByIndex(aliases, OID_IF_ALIAS, ifIndex);

                // gasim IP-ul interfetei dupa ifIndex
                String ip      = ifIndexToIp.get(ifIndex);
                String netmask = ip != null ? ifIndexToNetmask.get(ip) : null;
                Integer prefix = netmask != null ? netmaskToPrefix(netmask) : null;

                result.add(InterfaceEntry.builder()
                        .name(descr.value())
                        .macAddress(formatMac(mac))
                        .adminStatus(admin)
                        .operStatus(oper)
                        .speedMbps(parseLong(speedRaw) != null ? parseLong(speedRaw) / 1_000_000 : null)
                        .description(alias)
                        .ipAddress(ip)
                        .prefixLength(prefix)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Interface walk esuat pentru {}: {}", host, e.getMessage());
        }
        return result;
    }

    /**
     * Verifica daca un port name LLDP e util pentru afisare.
     * Filtreaza indexuri SNMP numerice pure ("526", "148") care nu sunt nume de interfata.
     * LLDP port ID subtype "locally assigned" poate returna indexul ca string.
     */
    private boolean isUsefulPortName(String name) {
        if (name == null || name.isBlank()) return false;
        // Pur numeric = ifIndex SNMP, nu e un nume de interfata
        return !name.trim().matches("\\d+");
    }

    // ---- Async helpers ----

    /** Ruleaza un SNMP walk asincron pe ForkJoinPool.commonPool(). */
    private CompletableFuture<List<SnmpEntry>> walkAsync(String host, String community, String baseOid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return walk(host, community, baseOid);
            } catch (Exception e) {
                log.debug("walkAsync esuat {}/{}: {}", host, baseOid, e.getMessage());
                return List.<SnmpEntry>of();
            }
        });
    }

    /**
     * Get sigur pe un CompletableFuture — NICIODATA nu arunca exceptie.
     * Gestioneaza InterruptedException (restaureaza flag-ul de interrupt),
     * TimeoutException (canceleaza future-ul) si orice alta exceptie.
     */
    private List<SnmpEntry> safeGet(CompletableFuture<List<SnmpEntry>> future, int timeoutSec) {
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restauram flag-ul de interrupt
            future.cancel(true);
            return List.of();
        } catch (TimeoutException e) {
            future.cancel(true);
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- Private helpers ----

    private String getScalar(String host, String community, String oid) throws IOException {
        TransportMapping<?> transport = new DefaultUdpTransportMapping();
        Snmp snmp = new Snmp(transport);
        transport.listen();
        try {
            CommunityTarget<Address> target = buildTarget(host, community);
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(oid)));
            pdu.setType(PDU.GET);
            ResponseEvent<Address> response = snmp.send(pdu, target);
            if (response != null && response.getResponse() != null
                    && !response.getResponse().getVariableBindings().isEmpty()) {
                return response.getResponse().get(0).getVariable().toString();
            }
            return null;
        } finally {
            snmp.close();
            transport.close();
        }
    }

    private List<SnmpEntry> walk(String host, String community, String baseOid) throws IOException {
        List<SnmpEntry> results = new ArrayList<>();
        TransportMapping<?> transport = new DefaultUdpTransportMapping();
        Snmp snmp = new Snmp(transport);
        transport.listen();
        try {
            CommunityTarget<Address> target = buildTarget(host, community);
            OID currentOid = new OID(baseOid);
            OID rootOid    = new OID(baseOid);

            while (true) {
                PDU pdu = new PDU();
                pdu.add(new VariableBinding(currentOid));
                pdu.setType(PDU.GETNEXT);

                ResponseEvent<Address> response = snmp.send(pdu, target);
                if (response == null || response.getResponse() == null) break;

                VariableBinding vb = response.getResponse().get(0);
                if (vb == null || vb.getOid() == null) break;
                if (!vb.getOid().startsWith(rootOid)) break;
                if (vb.getOid().compareTo(currentOid) <= 0) break;

                String val = vb.getVariable().toString();
                // ignoram valori goale sau "noSuchInstance"
                if (val != null && !val.equals("noSuchInstance") && !val.equals("noSuchObject")) {
                    results.add(new SnmpEntry(vb.getOid().toString(), val));
                }
                currentOid = vb.getOid();
            }
        } finally {
            snmp.close();
            transport.close();
        }
        return results;
    }

    private CommunityTarget<Address> buildTarget(String host, String community) {
        CommunityTarget<Address> target = new CommunityTarget<>();
        target.setCommunity(new OctetString(community));
        target.setAddress(new UdpAddress(host + "/161"));
        target.setRetries(retries);
        target.setTimeout(timeoutMs);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    private String stripPrefix(String fullOid, String baseOid) {
        if (fullOid.startsWith(baseOid)) {
            String rest = fullOid.substring(baseOid.length());
            return rest.startsWith(".") ? rest.substring(1) : rest;
        }
        return fullOid;
    }

    private String findByIndex(List<SnmpEntry> entries, String baseOid, String index) {
        String target = baseOid + "." + index;
        return entries.stream()
                .filter(e -> e.oid().equals(target))
                .map(SnmpEntry::value)
                .findFirst().orElse(null);
    }

    private String findByOidSuffix(List<SnmpEntry> entries, String baseOid, String suffix) {
        String target = baseOid + "." + suffix;
        return entries.stream()
                .filter(e -> e.oid().equals(target))
                .map(SnmpEntry::value)
                .findFirst().orElse(null);
    }

    private String mapStatus(String raw) {
        if (raw == null) return null;
        return switch (raw.trim()) {
            case "1" -> "up";
            case "2" -> "down";
            case "3" -> "testing";
            default -> raw;
        };
    }

    private String formatMac(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // SNMP poate returna MAC ca "0:a:b:c:d:e" sau ca octet string hex
        // normalizam la "aa:bb:cc:dd:ee:ff"
        raw = raw.trim();
        if (raw.contains(":") && raw.split(":").length == 6) {
            return Arrays.stream(raw.split(":"))
                    .map(s -> String.format("%02x", Integer.parseInt(s, 16)))
                    .reduce((a, b) -> a + ":" + b).orElse(raw);
        }
        return raw;
    }

    private Long parseLong(String val) {
        if (val == null) return null;
        try { return Long.parseLong(val.trim()); } catch (NumberFormatException e) { return null; }
    }

    private Integer netmaskToPrefix(String netmask) {
        try {
            long mask = 0;
            for (String part : netmask.split("\\.")) {
                mask = (mask << 8) | Integer.parseInt(part);
            }
            int prefix = 0;
            for (int i = 31; i >= 0; i--) {
                if ((mask & (1L << i)) != 0) prefix++;
                else break;
            }
            return prefix;
        } catch (Exception e) { return null; }
    }

    private record SnmpEntry(String oid, String value) {}

    @Data @Builder
    public static class LldpNeighbor {
        private String remoteSystemName;
        private String remotePortId;
        private String remoteChassisId;
        private String localPortId;
    }

    @Data @Builder
    public static class ArpEntry {
        private String ipAddress;
        private String macAddress;
    }

    @Data @Builder
    public static class InterfaceEntry {
        private String name;
        private String macAddress;
        private String adminStatus;
        private String operStatus;
        private Long   speedMbps;
        private String description;
        private String ipAddress;
        private Integer prefixLength;
    }
}
