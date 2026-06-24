package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;

import java.util.List;

/**
 * Contract comun pentru toti vendorii suportati.
 *
 * Fiecare vendor (Juniper/Junos, Arista/EOS) implementeaza aceste comenzi
 * folosind sintaxa proprie de CLI, dar din perspectiva DiscoveryEngineService
 * apelurile sunt identice - aici e polimorfismul care face discovery-ul
 * multi-vendor posibil fara if/else pe vendor in restul codului.
 */
public interface VendorAdapter {

    Vendor getVendor();

    /**
     * Comenzile de configurare necesare pentru a activa SNMP v2c si LLDP,
     * daca nu sunt deja active. Idempotente - se pot rula de mai multe ori
     * fara efecte secundare (Junos: "set" e idempotent; EOS: la fel).
     */
    List<String> getBootstrapConfigCommands(String snmpCommunity);

    /** Comanda CLI pentru a afla hostname-ul device-ului. */
    String getShowHostnameCommand();

    /** Comanda CLI pentru a afla modelul si versiunea de OS. */
    String getShowVersionCommand();

    /** Comanda CLI pentru a afla serialul chassis-ului. */
    String getShowSerialCommand();

    /**
     * Parseaza output-ul brut al comenzii "show version" (sau echivalent)
     * pentru a extrage modelul si versiunea OS. Fiecare vendor are format diferit.
     */
    ParsedVersionInfo parseVersionOutput(String rawOutput);

    record ParsedVersionInfo(String model, String osVersion, String serialNumber) {}
}
