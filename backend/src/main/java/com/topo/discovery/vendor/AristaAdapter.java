package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter pentru echipamente Arista (EOS / cEOS).
 *
 * Sintaxa EOS e stil Cisco-like (configure terminal, comenzi line-based),
 * diferita de stilul "set" al Junos - de aceea avem nevoie de adaptere separate.
 */
@Component
public class AristaAdapter implements VendorAdapter {

    @Override
    public Vendor getVendor() {
        return Vendor.ARISTA;
    }

    @Override
    public List<String> getBootstrapConfigCommands(String snmpCommunity) {
        return List.of(
                "configure terminal",
                "snmp-server community " + snmpCommunity + " ro",
                "lldp run",
                "end",
                "write memory"
        );
    }

    @Override
    public String getShowHostnameCommand() {
        return "show hostname";
    }

    @Override
    public String getShowVersionCommand() {
        return "show version";
    }

    @Override
    public String getShowSerialCommand() {
        return "show version | include Serial";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String rawOutput) {
        // Exemplu linie EOS: "Arista cEOS-LAB"
        String model = extract(rawOutput, "Arista\\s+(\\S+)");
        // Exemplu: "Software image version: 4.29.2F"
        String osVersion = extract(rawOutput, "Software image version:\\s*(\\S+)");
        String serial = extract(rawOutput, "Serial number:\\s*(\\S+)");
        return new ParsedVersionInfo(model, osVersion, serial);
    }

    private String extract(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
