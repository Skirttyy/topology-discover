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

        // Model: "board-name: RB3011UiAS-RM" sau "board-name: CHR" (Cloud Hosted Router)
        String model = extract(raw, "(?m)^\\s*board-name:\\s*(.+)$");
        if (model == null) {
            // Fallback: "platform: MikroTik"
            String platform = extract(raw, "(?m)^\\s*platform:\\s*(.+)$");
            if (platform != null) model = "MikroTik " + platform.trim();
        }

        // Versiune RouterOS: "version: 7.14.3 (stable)" sau "version: 7.14.3"
        String version = extract(raw, "(?m)^\\s*version:\\s*([\\d\\.]+(?:\\s+\\(\\S+\\))?)$");
        if (version == null) {
            // Fallback: extrage prima secventa de versiune
            version = extract(raw, "(?m)^\\s*version:\\s*(\\S+)$");
        }
        // Scoatem "(stable)" din versiune
        if (version != null) version = version.replaceAll("\\s*\\(\\w+\\)\\s*", "").trim();

        // Serial (disponibil pe RouterBOARD, nu pe CHR)
        String serial = extract(raw, "(?m)^\\s*serial-number:\\s*(\\S+)$");

        return new ParsedVersionInfo(hostname, model, version, serial);
    }

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
