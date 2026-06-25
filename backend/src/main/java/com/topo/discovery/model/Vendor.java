package com.topo.discovery.model;

public enum Vendor {
    JUNIPER,
    ARISTA,
    UNKNOWN;

    /**
     * Detecteaza vendorul din sysDescr SNMP sau din output-ul SSH show version.
     * Folosit atat pentru detectie initiala cat si ca fallback.
     */
    public static Vendor detect(String rawText) {
        if (rawText == null) return UNKNOWN;
        String lower = rawText.toLowerCase();
        if (lower.contains("junos") || lower.contains("juniper")) return JUNIPER;
        if (lower.contains("arista") || lower.contains("eos")) return ARISTA;
        return UNKNOWN;
    }
}
