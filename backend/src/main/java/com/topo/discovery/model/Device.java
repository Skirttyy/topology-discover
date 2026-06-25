package com.topo.discovery.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reprezinta un nod din topologie (router, switch).
 *
 * Credentialele (username/password) sunt stocate criptate folosind Jasypt
 * la nivel de aplicatie (vezi CredentialEncryptionService) - NU se bazeaza
 * pe criptarea automata a proprietatilor Jasypt (aceea e pt application.yml),
 * ci pe criptare explicita inainte de persistare in DB.
 */
@Entity
@Table(name = "devices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String managementIp;

    private String hostname;

    @Enumerated(EnumType.STRING)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DeviceStatus status = DeviceStatus.DISCOVERED;

    private String model;
    private String osVersion;
    private String serialNumber;

    @Column(length = 1000)
    private String sysDescr; // SNMP sysDescr brut - util pentru vendor detection fallback

    // Credentiale criptate (Jasypt, criptare la nivel de camp - vezi CredentialEncryptionService)
    private String sshUsername;

    @Column(length = 1024)
    private String sshPasswordEncrypted;

    // SNMP community criptat la fel
    @Column(length = 1024)
    private String snmpCommunityEncrypted;

    // a fost descoperit automat (din LLDP/ARP) sau adaugat manual ca seed?
    @Builder.Default
    private boolean seedDevice = false;

    private LocalDateTime firstDiscoveredAt;
    private LocalDateTime lastPolledAt;

    @Column(length = 2000)
    private String lastError;

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NetworkInterface> interfaces = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (firstDiscoveredAt == null) {
            firstDiscoveredAt = LocalDateTime.now();
        }
    }
}
