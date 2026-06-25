package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AristaAdapter implements VendorAdapter {

    @Override public Vendor getVendor() { return Vendor.ARISTA; }

    @Override
    public String getShowHostnameCommand() {
        return "show hostname";
    }

    @Override
    public String getShowVersionCommand() {
        return "show version";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String raw) {
        if (raw == null) return new ParsedVersionInfo(null, null, null, null);
        String hostname = extract(raw, "(?i)hostname:\\s*(\\S+)");
        String model    = extract(raw, "(?i)^(Arista\\s+\\S+)", Pattern.MULTILINE);
        String os       = extract(raw, "(?i)Software image version:\\s*(\\S+)");
        String serial   = extract(raw, "(?i)Serial number:\\s*(\\S+)");
        return new ParsedVersionInfo(hostname, model, os, serial);
    }

    private String extract(String text, String regex) {
        return extract(text, regex, 0);
    }

    private String extract(String text, String regex, int flags) {
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
