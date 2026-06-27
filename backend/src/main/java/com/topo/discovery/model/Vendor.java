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

    /**
     * Detecteaza vendorul din sysObjectID (OID enterprise, 1.3.6.1.2.1.1.2.0).
     * E semnalul cel mai fiabil si universal — nu depinde de parsarea textului
     * din sysDescr, care poate fi gol sau formatat diferit intre versiuni.
     *
     * IANA enterprise numbers: Juniper=2636, MikroTik=14988, Arista=30065.
     */
    public static Vendor detectFromSysObjectId(String oid) {
        if (oid == null) return UNKNOWN;
        String o = oid.trim();
        if (o.startsWith("1.3.6.1.4.1.2636"))  return JUNIPER;
        if (o.startsWith("1.3.6.1.4.1.14988")) return MIKROTIK;
        if (o.startsWith("1.3.6.1.4.1.30065")) return ARISTA;
        return UNKNOWN;
    }
}
