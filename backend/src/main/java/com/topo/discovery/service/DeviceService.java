package com.topo.discovery.service;

import com.topo.discovery.collector.SshCommandExecutor;
import com.topo.discovery.dto.CreateDeviceRequest;
import com.topo.discovery.model.Device;
import com.topo.discovery.model.DeviceStatus;
import com.topo.discovery.repository.DeviceRepository;
import com.topo.discovery.security.CredentialEncryptionService;
import com.topo.discovery.vendor.VendorAdapter;
import com.topo.discovery.vendor.VendorAdapterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final CredentialEncryptionService encryptionService;
    private final SshCommandExecutor sshExecutor;
    private final VendorAdapterFactory vendorAdapterFactory;

    @Value("${discovery.snmp.community}")
    private String defaultSnmpCommunity;

    private static final int SSH_PORT = 22;

    public Device createSeedDevice(CreateDeviceRequest request) {
        if (deviceRepository.existsByManagementIp(request.getManagementIp())) {
            throw new IllegalStateException("Exista deja un device cu IP-ul " + request.getManagementIp());
        }

        String community = request.getSnmpCommunity() != null ? request.getSnmpCommunity() : defaultSnmpCommunity;

        Device device = Device.builder()
                .managementIp(request.getManagementIp())
                .vendor(request.getVendor())
                .sshUsername(request.getSshUsername())
                .sshPasswordEncrypted(encryptionService.encrypt(request.getSshPassword()))
                .snmpCommunityEncrypted(encryptionService.encrypt(community))
                .status(DeviceStatus.DISCOVERED)
                .seedDevice(true)
                .build();

        return deviceRepository.save(device);
    }

    /** Folosit de SubnetScannerService - creeaza un device "schelet" pentru un IP gasit viu, fara duplicare. */
    public Device createSeedDeviceIfAbsent(String ip, com.topo.discovery.model.Vendor vendor,
                                             String sshUsername, String sshPassword, String snmpCommunity) {
        Optional<Device> existing = deviceRepository.findByManagementIp(ip);
        if (existing.isPresent()) {
            return existing.get();
        }

        String community = snmpCommunity != null ? snmpCommunity : defaultSnmpCommunity;

        Device device = Device.builder()
                .managementIp(ip)
                .vendor(vendor)
                .sshUsername(sshUsername)
                .sshPasswordEncrypted(encryptionService.encrypt(sshPassword))
                .snmpCommunityEncrypted(encryptionService.encrypt(community))
                .status(DeviceStatus.DISCOVERED)
                .seedDevice(true)
                .build();

        return deviceRepository.save(device);
    }

    public List<Device> findAll() {
        return deviceRepository.findAll();
    }

    public Device findById(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device negasit, id=" + id));
    }

    public Optional<Device> findByManagementIp(String ip) {
        return deviceRepository.findByManagementIp(ip);
    }

    public Device save(Device device) {
        return deviceRepository.save(device);
    }

    public void deleteById(Long id) {
        deviceRepository.deleteById(id);
    }

    /** Decripteaza parola SSH a unui device - folosit DOAR intern, in motorul de discovery. */
    public String decryptSshPassword(Device device) {
        return encryptionService.decrypt(device.getSshPasswordEncrypted());
    }

    public String decryptSnmpCommunity(Device device) {
        return encryptionService.decrypt(device.getSnmpCommunityEncrypted());
    }

    /**
     * Incearca sa detecteze automat vendorul unui device pe baza banner-ului SSH
     * sau a raspunsului la o comanda generica. Util cand scanam un subnet cu
     * vendori micsti (desi fluxul principal presupune un vendor cunoscut per scan).
     */
    public com.topo.discovery.model.Vendor detectVendor(String ip, String username, String password) {
        try {
            String output = sshExecutor.executeCommand(ip, SSH_PORT, username, password, "show version");
            if (output.toLowerCase().contains("junos")) {
                return com.topo.discovery.model.Vendor.JUNIPER;
            }
            if (output.toLowerCase().contains("arista") || output.toLowerCase().contains("eos")) {
                return com.topo.discovery.model.Vendor.ARISTA;
            }
        } catch (Exception e) {
            log.warn("Detectie automata vendor esuata pentru {}: {}", ip, e.getMessage());
        }
        return com.topo.discovery.model.Vendor.UNKNOWN;
    }
}
