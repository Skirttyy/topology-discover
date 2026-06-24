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
import java.util.ArrayList;
import java.util.List;

/**
 * Colectează date prin SNMP v2c folosind MIB-uri standard (nu proprietare),
 * astfel încât același cod să funcționeze identic pe Juniper și Arista:
 *
 * - LLDP-MIB (RFC 2922 / IEEE 802.1AB) -> vecini L2 direcți
 * - IP-MIB / ipNetToMediaTable -> tabela ARP (IP <-> MAC) pentru inferența L3
 * - IF-MIB -> lista de interfețe, status, viteză
 *
 * Folosirea MIB-urilor standard (în loc de comenzi CLI proprietare pt fiecare
 * lucru) e parte din motivul pentru care SNMP completează LLDP: ne dă un
 * fallback uniform peste vendori.
 */
@Component
@Slf4j
public class SnmpCollector {

    @Value("${discovery.snmp.timeout-ms}")
    private int timeoutMs;

    @Value("${discovery.snmp.retries}")
    private int retries;

    // OID-uri standard LLDP-MIB
    private static final String OID_LLDP_REM_SYS_NAME = "1.0.8802.1.1.2.1.4.1.1.9";
    private static final String OID_LLDP_REM_PORT_ID = "1.0.8802.1.1.2.1.4.1.1.7";
    private static final String OID_LLDP_REM_CHASSIS_ID = "1.0.8802.1.1.2.1.4.1.1.5";
    private static final String OID_LLDP_LOC_PORT_DESCR = "1.0.8802.1.1.2.1.3.7.1.4";

    // OID standard ARP (ipNetToMediaTable)
    private static final String OID_IP_NET_TO_MEDIA_PHYS_ADDRESS = "1.3.6.1.2.1.4.22.1.2";

    // OID-uri standard IF-MIB
    private static final String OID_IF_DESCR = "1.3.6.1.2.1.2.2.1.2";
    private static final String OID_IF_PHYS_ADDRESS = "1.3.6.1.2.1.2.2.1.6";
    private static final String OID_IF_ADMIN_STATUS = "1.3.6.1.2.1.2.2.1.7";
    private static final String OID_IF_OPER_STATUS = "1.3.6.1.2.1.2.2.1.8";
    private static final String OID_IF_SPEED = "1.3.6.1.2.1.2.2.1.5";

    public List<LldpNeighbor> walkLldpNeighbors(String host, String community) {
        List<LldpNeighbor> neighbors = new ArrayList<>();
        try {
            List<SnmpEntry> sysNames = walk(host, community, OID_LLDP_REM_SYS_NAME);
            List<SnmpEntry> portIds = walk(host, community, OID_LLDP_REM_PORT_ID);
            List<SnmpEntry> chassisIds = walk(host, community, OID_LLDP_REM_CHASSIS_ID);

            for (SnmpEntry sysNameEntry : sysNames) {
                // index-ul comun (ultimele componente ale OID-ului) leagă tabelele intre ele
                String index = extractTrailingIndex(sysNameEntry.oid(), OID_LLDP_REM_SYS_NAME);

                String portId = findByIndex(portIds, OID_LLDP_REM_PORT_ID, index);
                String chassisId = findByIndex(chassisIds, OID_LLDP_REM_CHASSIS_ID, index);

                neighbors.add(LldpNeighbor.builder()
                        .remoteSystemName(sysNameEntry.value())
                        .remotePortId(portId)
                        .remoteChassisId(chassisId)
                        .build());
            }
        } catch (IOException e) {
            log.warn("LLDP walk esuat pentru {}: {}", host, e.getMessage());
        }
        return neighbors;
    }

    public List<ArpEntry> walkArpTable(String host, String community) {
        List<ArpEntry> entries = new ArrayList<>();
        try {
            List<SnmpEntry> macEntries = walk(host, community, OID_IP_NET_TO_MEDIA_PHYS_ADDRESS);
            for (SnmpEntry entry : macEntries) {
                // OID format: ...22.1.2.<ifIndex>.<ip1>.<ip2>.<ip3>.<ip4>
                String suffix = entry.oid().substring(OID_IP_NET_TO_MEDIA_PHYS_ADDRESS.length() + 1);
                String[] parts = suffix.split("\\.");
                if (parts.length >= 5) {
                    String ip = String.join(".", parts[parts.length - 4], parts[parts.length - 3],
                            parts[parts.length - 2], parts[parts.length - 1]);
                    entries.add(ArpEntry.builder()
                            .ipAddress(ip)
                            .macAddress(entry.value())
                            .build());
                }
            }
        } catch (IOException e) {
            log.warn("ARP walk esuat pentru {}: {}", host, e.getMessage());
        }
        return entries;
    }

    public List<InterfaceEntry> walkInterfaces(String host, String community) {
        List<InterfaceEntry> result = new ArrayList<>();
        try {
            List<SnmpEntry> descrs = walk(host, community, OID_IF_DESCR);
            List<SnmpEntry> macs = walk(host, community, OID_IF_PHYS_ADDRESS);
            List<SnmpEntry> adminStatuses = walk(host, community, OID_IF_ADMIN_STATUS);
            List<SnmpEntry> operStatuses = walk(host, community, OID_IF_OPER_STATUS);
            List<SnmpEntry> speeds = walk(host, community, OID_IF_SPEED);

            for (SnmpEntry descr : descrs) {
                String index = extractTrailingIndex(descr.oid(), OID_IF_DESCR);
                result.add(InterfaceEntry.builder()
                        .name(descr.value())
                        .macAddress(findByIndex(macs, OID_IF_PHYS_ADDRESS, index))
                        .adminStatus(mapIfStatus(findByIndex(adminStatuses, OID_IF_ADMIN_STATUS, index)))
                        .operStatus(mapIfStatus(findByIndex(operStatuses, OID_IF_OPER_STATUS, index)))
                        .speedBps(parseLongSafe(findByIndex(speeds, OID_IF_SPEED, index)))
                        .build());
            }
        } catch (IOException e) {
            log.warn("Interface walk esuat pentru {}: {}", host, e.getMessage());
        }
        return result;
    }

    /** SNMP GETNEXT/walk generic peste un sub-tree OID. */
    private List<SnmpEntry> walk(String host, String community, String baseOid) throws IOException {
        List<SnmpEntry> results = new ArrayList<>();

        TransportMapping<?> transport = new DefaultUdpTransportMapping();
        Snmp snmp = new Snmp(transport);
        transport.listen();

        try {
            CommunityTarget<Address> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(community));
            target.setAddress(new UdpAddress(host + "/161"));
            target.setRetries(retries);
            target.setTimeout(timeoutMs);
            target.setVersion(SnmpConstants.version2c);

            OID currentOid = new OID(baseOid);
            OID rootOid = new OID(baseOid);

            while (true) {
                PDU pdu = new PDU();
                pdu.add(new VariableBinding(currentOid));
                pdu.setType(PDU.GETNEXT);

                ResponseEvent<Address> response = snmp.send(pdu, target);
                if (response == null || response.getResponse() == null) {
                    break;
                }

                VariableBinding vb = response.getResponse().get(0);
                if (vb.getOid() == null || !vb.getOid().startsWith(rootOid)) {
                    break;
                }
                if (vb.getOid().compareTo(currentOid) <= 0) {
                    break; // protectie impotriva buclelor infinite
                }

                results.add(new SnmpEntry(vb.getOid().toString(), vb.getVariable().toString()));
                currentOid = vb.getOid();
            }
        } finally {
            snmp.close();
        }

        return results;
    }

    private String extractTrailingIndex(String fullOid, String baseOid) {
        return fullOid.substring(baseOid.length() + 1);
    }

    private String findByIndex(List<SnmpEntry> entries, String baseOid, String index) {
        String targetOid = baseOid + "." + index;
        return entries.stream()
                .filter(e -> e.oid().equals(targetOid))
                .map(SnmpEntry::value)
                .findFirst()
                .orElse(null);
    }

    private String mapIfStatus(String rawValue) {
        if (rawValue == null) return null;
        return switch (rawValue.trim()) {
            case "1" -> "up";
            case "2" -> "down";
            default -> rawValue;
        };
    }

    private Long parseLongSafe(String value) {
        try {
            return value != null ? Long.parseLong(value.trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SnmpEntry(String oid, String value) {}

    @Data
    @Builder
    public static class LldpNeighbor {
        private String remoteSystemName;
        private String remotePortId;
        private String remoteChassisId;
    }

    @Data
    @Builder
    public static class ArpEntry {
        private String ipAddress;
        private String macAddress;
    }

    @Data
    @Builder
    public static class InterfaceEntry {
        private String name;
        private String macAddress;
        private String adminStatus;
        private String operStatus;
        private Long speedBps;
    }
}
