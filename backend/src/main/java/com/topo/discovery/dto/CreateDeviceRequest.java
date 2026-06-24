package com.topo.discovery.dto;

import com.topo.discovery.model.Vendor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Request pentru adaugarea unui singur device "seed" - punctul de pornire
 * pentru BFS discovery.
 */
@Data
public class CreateDeviceRequest {

    @NotBlank
    @Pattern(regexp = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$",
              message = "managementIp trebuie sa fie o adresa IPv4 valida")
    private String managementIp;

    @NotNull
    private Vendor vendor;

    @NotBlank
    private String sshUsername;

    @NotBlank
    private String sshPassword;

    // optional - daca nu e dat, se foloseste community-ul default din application.yml
    private String snmpCommunity;
}
