package com.topo.discovery.dto;

import com.topo.discovery.model.Vendor;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Request pentru a scana unul sau mai multe subnet-uri (ex: 192.168.100.0/24),
 * gasi device-urile vii pe portul SSH si le adauga ca seed devices.
 *
 * Suporta atat campul vechi {@code subnet} (un singur CIDR — back-compat) cat si
 * {@code subnets} (lista de CIDR-uri). {@link #getAllSubnets()} le combina,
 * elimina duplicatele si blank-urile.
 *
 * Toate device-urile gasite sunt presupuse a folosi aceleasi credentiale -
 * tipic pentru un lab EVE-NG controlat. Daca lab-ul are credentiale diferite
 * per device, foloseste in schimb endpoint-ul /api/devices pentru fiecare.
 */
@Data
public class ScanSubnetRequest {

    private static final String CIDR_REGEX =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/(3[0-2]|[1-2]?[0-9])$";

    /** Compatibilitate inapoi: un singur CIDR. Optional daca {@code subnets} e populat. */
    @Pattern(regexp = CIDR_REGEX, message = "subnet trebuie in format CIDR, ex: 192.168.100.0/24")
    private String subnet;

    /** Lista de subnet-uri (CIDR). Fiecare element e validat individual. */
    private List<@Pattern(regexp = CIDR_REGEX,
            message = "fiecare subnet trebuie in format CIDR, ex: 192.168.100.0/24") String> subnets;

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

    /** Combina {@code subnet} + {@code subnets} intr-o lista unica, fara duplicate / blank-uri. */
    public List<String> getAllSubnets() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (subnet != null && !subnet.isBlank()) all.add(subnet.trim());
        if (subnets != null) {
            for (String s : subnets) {
                if (s != null && !s.isBlank()) all.add(s.trim());
            }
        }
        return new ArrayList<>(all);
    }

    /** Validare: trebuie cel putin un subnet, fie in {@code subnet}, fie in {@code subnets}. */
    @AssertTrue(message = "trebuie cel putin un subnet (CIDR)")
    public boolean isAtLeastOneSubnet() {
        return !getAllSubnets().isEmpty();
    }
}
