package com.topo.discovery.dto;

import com.topo.discovery.model.Vendor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request pentru a scana un intreg subnet (ex: 192.168.100.0/24),
 * gasi device-urile vii pe portul SSH si le adauga ca seed devices.
 *
 * Toate device-urile gasite in subnet sunt presupuse a fi de acelasi vendor
 * si a folosi aceleasi credentiale - tipic pentru un lab EVE-NG controlat.
 * Daca lab-ul are vendori micsti pe acelasi subnet, foloseste in schimb
 * endpoint-ul /api/devices pentru fiecare device individual.
 */
@Data
public class ScanSubnetRequest {

    @NotBlank
    @jakarta.validation.constraints.Pattern(
            regexp = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/(3[0-2]|[1-2]?[0-9])$",
            message = "subnet trebuie in format CIDR, ex: 192.168.100.0/24")
    private String subnet;

    @NotNull
    private Vendor vendor;

    @NotBlank
    private String sshUsername;

    @NotBlank
    private String sshPassword;

    private String snmpCommunity;

    // daca true, dupa ce gaseste device-urile vii, porneste automat
    // bootstrap config (activare SNMP+LLDP) + discovery BFS de pe toate
    private boolean autoStartDiscovery = true;
}
