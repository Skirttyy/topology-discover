package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;

/**
 * Contract comun pentru vendor adapters.
 * FARA bootstrap - SNMP si LLDP sunt deja active pe device-uri.
 * Doar comenzi SSH read-only pentru a extrage informatii.
 */
public interface VendorAdapter {

    Vendor getVendor();

    String getShowHostnameCommand();
    String getShowVersionCommand();

    ParsedVersionInfo parseVersionOutput(String rawOutput);

    record ParsedVersionInfo(String hostname, String model, String osVersion, String serialNumber) {}
}
