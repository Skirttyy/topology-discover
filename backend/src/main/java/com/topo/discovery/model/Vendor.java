package com.topo.discovery.model;

public enum Vendor {
    JUNIPER,
    ARISTA,
    MIKROTIK,
    UNKNOWN;

    public static Vendor detect(String rawText) {
        if (rawText == null) return UNKNOWN;
        String lower = rawText.toLowerCase();

        if (lower.contains("junos") || lower.contains("juniper")) return JUNIPER;
        if (lower.contains("arista") || lower.contains("eos version")) return ARISTA;

        // MikroTik: multiple patterns — depinde de versiune si configuratie
        // "RouterOS 7.14.3", "MikroTik RouterOS", "Mikrotik", "board-name: CHR",
        // "/system resource", "platform: MikroTik"
        if (lower.contains("routeros")
                || lower.contains("mikrotik")
                || lower.contains("board-name")
                || lower.contains("platform: mikrotik")
                || lower.contains("/system")) return MIKROTIK;

        return UNKNOWN;
    }
}
