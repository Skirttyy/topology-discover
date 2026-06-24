package com.topo.discovery.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Trimite notificari de progres catre frontend prin WebSocket (STOMP),
 * astfel incat UI-ul sa poata arata in timp real "Procesez device X..."
 * in loc sa faca polling agresiv pe REST API.
 *
 * Frontend-ul se aboneaza la /topic/discovery-progress.
 */
@Service
@RequiredArgsConstructor
public class DiscoveryProgressNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    private static final String TOPIC = "/topic/discovery-progress";

    public void notifyDeviceProcessing(String deviceIp) {
        messagingTemplate.convertAndSend(TOPIC, Map.of(
                "type", "PROCESSING",
                "deviceIp", deviceIp
        ));
    }

    public void notifyCompleted() {
        messagingTemplate.convertAndSend(TOPIC, Map.of(
                "type", "COMPLETED"
        ));
    }

    public void notifyError(String message) {
        messagingTemplate.convertAndSend(TOPIC, Map.of(
                "type", "ERROR",
                "message", message
        ));
    }
}
