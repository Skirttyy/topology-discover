package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JuniperAdapter implements VendorAdapter {

    @Override public Vendor getVendor() { return Vendor.JUNIPER; }

    @Override
    public String getShowHostnameCommand() {
        return "show version | match Hostname";
    }

    @Override
    public String getShowVersionCommand() {
        return "show version | no-more";
    }

    @Override
    public ParsedVersionInfo parseVersionOutput(String raw) {
        if (raw == null) return new ParsedVersionInfo(null, null, null, null);
        String hostname = extract(raw, "(?i)hostname:\\s*(\\S+)");
        String model    = extract(raw, "(?i)model:\\s*(\\S+)");
        String os       = extract(raw, "(?i)junos:\\s*(\\S+)");
        String serial   = extract(raw, "(?i)chassis:\\s*(\\S+)");
        return new ParsedVersionInfo(hostname, model, os, serial);
    }

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
