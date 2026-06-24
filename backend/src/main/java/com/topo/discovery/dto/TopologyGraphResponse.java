package com.topo.discovery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Reprezentarea completa a topologiei, in formatul asteptat de React Flow
 * pe frontend: liste de noduri si muchii.
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
        private String id;            // device.id ca string
        private String label;         // hostname sau IP daca hostname lipseste
        private String vendor;
        private String status;
        private String managementIp;
        private String model;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphEdge {
        private String id;
        private String source;            // id device local
        private String target;            // id device remote
        private String sourceInterface;
        private String targetInterface;
        private String discoverySource;   // LLDP sau ARP_MAC_INFERENCE
    }
}
