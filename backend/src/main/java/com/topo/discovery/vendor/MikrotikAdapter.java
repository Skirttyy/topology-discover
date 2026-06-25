package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter pentru RouterOS (MikroTik).
 *
 * Comenzi folosite:
 *   /system resource print  -> model, versiune RouterOS
 *   /system identity print  -> hostname
 *
 * SNMP standard (IF-MIB, LLDP-MIB) functioneaza pe RouterOS >= 6.x cu pachetul
 * "ip neighbor" activat si SNMP configurat. Neighbor Discovery Protocol (NDP/CDP)
 * poate fi activat separat; LLDP e suportat din RouterOS 6.49+.
 */
@Component
public class MikrotikAdapter implements VendorAdapter {

    @Override
    public Vendor getVendor() { return Vendor.MIKROTIK; }

    @Override
    public String getShowHostnameCommand() {
        return "/system identity print";
    }

    @Override
    public String getShowVersionCommand() {
        // /system resource print returneaza model + versiune RouterOS
        return "/system resource print";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String raw) {
        if (raw == null) return new ParsedVersionInfo(null, null, null, null);

        // hostname din /system identity print (daca e inclus in output)
        String hostname = extract(raw, "(?i)name:\\s*(.+)");

        // board-name: RB3011UiAS-RM
        String model = extract(raw, "(?i)board-name:\\s*(.+)");
        if (model == null) model = extract(raw, "(?i)platform:\\s*(.+)");

        // version: 7.14.3 (stable)
        String version = extract(raw, "(?i)version:\\s*(\\S+)");

        // serial: nu e in /system resource, dar il putem extrage daca e in output
        String serial = extract(raw, "(?i)serial-number:\\s*(\\S+)");

        return new ParsedVersionInfo(hostname, model, version, serial);
    }

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
