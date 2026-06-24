package com.topo.discovery.model;

/**
 * Starea unui device in fluxul de discovery.
 */
public enum DeviceStatus {
    DISCOVERED,      // descoperit (din LLDP/ARP al altui device), inca neinterogat direct
    CONFIGURING,      // bootstrap config in curs (activare SNMP/LLDP)
    POLLING,          // SSH/SNMP polling in curs
    ACTIVE,           // polled cu succes, date proaspete
    UNREACHABLE,      // nu raspunde la SSH/SNMP
    ERROR             // eroare in timpul configurarii/pollingului
}
