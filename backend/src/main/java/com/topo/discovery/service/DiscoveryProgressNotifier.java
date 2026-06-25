package com.topo.discovery.service;

import com.topo.discovery.dto.TopologyGraphResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Trimite events progresive catre frontend prin WebSocket (STOMP).
 *
 * Frontend-ul primeste:
 * - NODE_DISCOVERED  -> un nod nou a aparut (il adauga in graf cu animatie)
 * - LINK_DISCOVERED  -> o muchie noua a aparut (o animeaza)
 * - NODE_UPDATED     -> un nod existent a primit date noi (hostname, model etc.)
 * - COMPLETED        -> discovery finalizat
 * - ERROR            -> eroare fatala
 */
@Service
@RequiredArgsConstructor
public class DiscoveryProgressNotifier {

    private final SimpMessagingTemplate messaging;
    private static final String TOPIC = "/topic/discovery-progress";

    public void notifyNodeDiscovered(TopologyGraphResponse.GraphNode node) {
        messaging.convertAndSend(TOPIC, Map.of(
                "type", "NODE_DISCOVERED",
                "node", node
        ));
    }

    public void notifyNodeUpdated(TopologyGraphResponse.GraphNode node) {
        messaging.convertAndSend(TOPIC, Map.of(
                "type", "NODE_UPDATED",
                "node", node
        ));
    }

    public void notifyLinkDiscovered(TopologyGraphResponse.GraphEdge edge) {
        messaging.convertAndSend(TOPIC, Map.of(
                "type", "LINK_DISCOVERED",
                "edge", edge
        ));
    }

    public void notifyProcessing(String deviceIp, String phase) {
        messaging.convertAndSend(TOPIC, Map.of(
                "type", "PROCESSING",
                "deviceIp", deviceIp,
                "phase", phase
        ));
    }

    public void notifyCompleted(int totalDevices, int totalLinks) {
        messaging.convertAndSend(TOPIC, Map.of(
                "type", "COMPLETED",
                "totalDevices", totalDevices,
                "totalLinks", totalLinks
        ));
    }

    public void notifyError(String message) {
        messaging.convertAndSend(TOPIC, Map.of(
                "type", "ERROR",
                "message", message
        ));
    }
}
