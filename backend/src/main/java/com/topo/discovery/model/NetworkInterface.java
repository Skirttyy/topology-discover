package com.topo.discovery.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * O interfata (port) a unui device. Contine atat informatia L2 (MAC),
 * cat si L3 (IP/subnet) daca interfata e configurata cu adresa IP.
 */
@Entity
@Table(name = "network_interfaces")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkInterface {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    private String name;          // ex: ge-0/0/1, Ethernet1
    private String macAddress;
    private String ipAddress;     // poate fi null daca interfata e doar L2
    private Integer prefixLength; // /24 etc, null daca nu are IP

    private String description;
    private String adminStatus;   // up/down
    private String operStatus;    // up/down
    private Long speedMbps;

    private String vlan;
}
