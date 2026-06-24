package com.topo.discovery.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * O muchie (link fizic) intre doua device-uri, descoperita prin LLDP
 * (in primul rand) sau dedusa din ARP/MAC tables (fallback).
 *
 * Folosim o pereche directionala (local -> remote) dar logic reprezinta
 * o singura legatura fizica; deduplicarea se face in GraphBuilderService.
 */
@Entity
@Table(name = "links")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "local_device_id", nullable = false)
    private Device localDevice;

    private String localInterfaceName;

    @ManyToOne
    @JoinColumn(name = "remote_device_id")
    private Device remoteDevice; // poate fi null daca vecinul nu a fost inca rezolvat la un Device cunoscut

    private String remoteInterfaceName;
    private String remoteSystemName;  // din LLDP, util chiar daca remoteDevice e null inca
    private String remoteChassisId;   // de regula MAC-ul chassis-ului, util pt matching

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DiscoverySource source = DiscoverySource.LLDP;

    private LocalDateTime discoveredAt;

    @PrePersist
    protected void onCreate() {
        if (discoveredAt == null) {
            discoveredAt = LocalDateTime.now();
        }
    }

    public enum DiscoverySource {
        LLDP, ARP_MAC_INFERENCE
    }
}
