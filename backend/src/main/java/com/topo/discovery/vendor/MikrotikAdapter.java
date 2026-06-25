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
        // Combinam identity + resource intr-un singur command SSH
        // (JSch executa fiecare comanda in propria sesiune, deci facem un singur apel)
        return "/system identity print\n/system resource print";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String raw) {
        if (raw == null || raw.isBlank()) return new ParsedVersionInfo(null, null, null, null);

        // Hostname: "name: MyRouter" din /system identity print
        String hostname = extract(raw, "(?m)^\\s*name:\\s*(.+)$");

        // Model/platform: "board-name: RB3011UiAS-RM" sau "platform: MikroTik"
        String model = extract(raw, "(?m)^\\s*board-name:\\s*(.+)$");
        if (model == null) model = extract(raw, "(?m)^\\s*platform:\\s*(.+)$");

        // Versiune RouterOS: "version: 7.14.3 (stable)"
        String version = extract(raw, "(?m)^\\s*version:\\s*(\\S+(?:\\s+\\(\\S+\\))?)$");
        // scurteaza "(stable)" daca e prea lung
        if (version != null) version = version.replaceAll("\\s*\\(\\w+\\)", "").trim();

        // Serial: nu apare in /system resource, dar unele modele il afiseaza
        String serial = extract(raw, "(?m)^\\s*serial-number:\\s*(\\S+)$");

        return new ParsedVersionInfo(hostname, model, version, serial);
    }

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
