package com.topo.discovery.service;

import com.topo.discovery.collector.SshCommandExecutor;
import com.topo.discovery.model.Device;
import com.topo.discovery.vendor.VendorAdapter;
import com.topo.discovery.vendor.VendorAdapterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Se conectează prin SSH la un device nou-adăugat și activează SNMP v2c +
 * LLDP, folosind comenzile specifice vendorului (via VendorAdapter).
 *
 * Acesta e exact lucrul cerut explicit: "codul sa includa o parte unde sa
 * pun subneturile si sa activeze tot ce are nevoie pt discovery" - device-urile
 * proaspăt găsite în lab nu trebuie pre-configurate manual cu SNMP/LLDP,
 * aplicația o face automat la primul contact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapConfigService {

    private final SshCommandExecutor sshExecutor;
    private final VendorAdapterFactory vendorAdapterFactory;

    private static final int SSH_PORT = 22;

    /**
     * Rulează secvența de bootstrap (activare SNMP+LLDP) pe device.
     * Idempotent - sigur de rulat chiar dacă SNMP/LLDP sunt deja active.
     *
     * @return true daca bootstrap-ul a reusit, false altfel (vezi log pentru detalii)
     */
    public boolean bootstrap(Device device, String plainSshPassword, String plainSnmpCommunity) {
        VendorAdapter adapter = vendorAdapterFactory.getAdapter(device.getVendor());

        try {
            log.info("Bootstrap config (SNMP+LLDP) pe {} ({})", device.getManagementIp(), device.getVendor());

            var commands = adapter.getBootstrapConfigCommands(plainSnmpCommunity);

            sshExecutor.executeCommandSequence(
                    device.getManagementIp(),
                    SSH_PORT,
                    device.getSshUsername(),
                    plainSshPassword,
                    commands
            );

            log.info("Bootstrap reusit pe {}", device.getManagementIp());
            return true;
        } catch (Exception e) {
            log.error("Bootstrap esuat pe {}: {}", device.getManagementIp(), e.getMessage());
            device.setLastError("Bootstrap config esuat: " + e.getMessage());
            return false;
        }
    }
}
