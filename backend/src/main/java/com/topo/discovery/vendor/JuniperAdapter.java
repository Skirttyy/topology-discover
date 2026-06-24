package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter pentru echipamente Juniper (Junos / vJunos / vMX).
 *
 * Sintaxa Junos foloseste "set" pentru configurare (configuration mode,
 * stil "set-based") si comenzi "show" cu | pentru output structurat.
 */
@Component
public class JuniperAdapter implements VendorAdapter {

    @Override
    public Vendor getVendor() {
        return Vendor.JUNIPER;
    }

    @Override
    public List<String> getBootstrapConfigCommands(String snmpCommunity) {
        return List.of(
                "configure",
                "set snmp community " + snmpCommunity + " authorization read-only",
                "set protocols lldp interface all",
                "set protocols lldp port-id-subtype interface-name",
                "set snmp view all-mib oid .1 include",
                "commit and-quit"
        );
    }

    @Override
    public String getShowHostnameCommand() {
        return "show configuration system host-name";
    }

    @Override
    public String getShowVersionCommand() {
        return "show version | no-more";
    }

    @Override
    public String getShowSerialCommand() {
        return "show chassis hardware | match Chassis";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String rawOutput) {
        // Exemplu linie Junos: "Model: vmx" sau "Model: vsrx"
        String model = extract(rawOutput, "Model:\\s*(\\S+)");
        // Exemplu: "Junos: 21.4R1.12"
        String osVersion = extract(rawOutput, "Junos:\\s*(\\S+)");
        return new ParsedVersionInfo(model, osVersion, null);
    }

    private String extract(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
