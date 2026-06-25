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
        if (lower.contains("arista") || lower.contains("eos")) return ARISTA;
        if (lower.contains("routeros") || lower.contains("mikrotik")) return MIKROTIK;
        return UNKNOWN;
    }
}
