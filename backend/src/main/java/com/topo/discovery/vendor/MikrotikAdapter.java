package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter pentru RouterOS (MikroTik).
 *
 * Comenzi SSH folosite:
 *   /system identity print  -> hostname
 *   /system resource print  -> model (board-name), versiune, arhitectura
 *
 * ATENTIE: RouterOS SSH are un prompt interactiv propriu, dar comenzile
 * non-interactive cu JSch functioneaza daca sunt trimise ca un singur string.
 * MikroTik seteaza hostname-ul cu "name" in /system identity.
 *
 * SNMP: RouterOS suporta IF-MIB standard. LLDP e disponibil din 6.49+.
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
        // Doar resource print — hostname vine din SNMP sysName sau din apel SSH separat
        // Nu combinam cu \n: JSch exec mode trimite comanda ca string literal,
        // nu o interpreteaza ca doua comenzi
        return "/system resource print";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String raw) {
        if (raw == null || raw.isBlank()) return new ParsedVersionInfo(null, null, null, null);

        // Hostname din /system identity print: "name: MyRouter"
        // sau din /system identity: poate aparea ca "   name: X"
        String hostname = extract(raw, "(?m)^\\s*name:\\s*(.+)$");

        // Model: "board-name: CHR" sau "board-name: RB3011UiAS-RM"
        // Pe CHR (QEMU), board-name poate fi "CHR QEMU Standard PC..." — luam doar primul token
        String model = extract(raw, "(?m)^\\s*board-name:\\s*(\\S+)");
        if (model == null) {
            // Fallback: platform name
            model = extract(raw, "(?m)^\\s*platform:\\s*(\\S+)");
        }

        // Versiune RouterOS: "version: 7.17 (stable)"
        // Nu folosim $ la final — unele outputuri au \r\n sau spatii dupa
        String version = extract(raw, "(?m)^\\s*version:\\s*([\\d][\\d\\.]+)");
        if (version != null) version = version.trim();

        // Serial (disponibil pe RouterBOARD, nu pe CHR)
        String serial = extract(raw, "(?m)^\\s*serial-number:\\s*(\\S+)$");

        return new ParsedVersionInfo(hostname, model, version, serial);
    }

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
