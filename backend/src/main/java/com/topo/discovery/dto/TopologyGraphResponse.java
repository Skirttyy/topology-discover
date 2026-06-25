package com.topo.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Raspunsul complet al topologiei pentru React Flow.
 * Acelasi format e folosit si pentru events WebSocket partiale
 * (un nod nou descoperit, un link nou) - frontend-ul merge incremental.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopologyGraphResponse {

    private List<GraphNode> nodes;
    private List<GraphEdge> edges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNode {
        private String id;
        private String label;       // hostname sau IP
        private String vendor;      // JUNIPER / ARISTA / UNKNOWN
        private String status;      // ACTIVE / ERROR / POLLING etc.
        private String managementIp;
        private String model;
        private String osVersion;
        private String serialNumber;
        private String sysDescr;    // SNMP sysDescr brut - util pt UNKNOWN
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphEdge {
        private String id;
        private String source;
        private String target;
        private String sourceInterface;
        private String targetInterface;
        private String discoverySource;  // LLDP / SNMP_ARP
    }
}
