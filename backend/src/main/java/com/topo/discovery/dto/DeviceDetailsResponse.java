package com.topo.discovery.dto;

import com.topo.discovery.model.Device;
import com.topo.discovery.model.DeviceStatus;
import com.topo.discovery.model.Vendor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Raspunsul cu detaliile unui device, expus la frontend cand userul
 * da click pe un nod. NU contine niciodata credentiale, nici criptate.
 */
@Data
@Builder
public class DeviceDetailsResponse {

    private Long id;
    private String managementIp;
    private String hostname;
    private Vendor vendor;
    private DeviceStatus status;
    private String model;
    private String osVersion;
    private String serialNumber;
    private boolean seedDevice;
    private LocalDateTime firstDiscoveredAt;
    private LocalDateTime lastPolledAt;
    private String lastError;
    private String sysDescr;
    private List<InterfaceResponse> interfaces;

    public static DeviceDetailsResponse from(Device device) {
        return DeviceDetailsResponse.builder()
                .id(device.getId())
                .managementIp(device.getManagementIp())
                .hostname(device.getHostname())
                .vendor(device.getVendor())
                .status(device.getStatus())
                .model(device.getModel())
                .osVersion(device.getOsVersion())
                .serialNumber(device.getSerialNumber())
                .seedDevice(device.isSeedDevice())
                .firstDiscoveredAt(device.getFirstDiscoveredAt())
                .lastPolledAt(device.getLastPolledAt())
                .lastError(device.getLastError())
                .sysDescr(device.getSysDescr())
                .interfaces(device.getInterfaces().stream()
                        .map(InterfaceResponse::from)
                        .toList())
                .build();
    }

    @Data
    @Builder
    public static class InterfaceResponse {
        private String name;
        private String macAddress;
        private String ipAddress;
        private Integer prefixLength;
        private String description;
        private String adminStatus;
        private String operStatus;
        private Long speedMbps;
        private String vlan;

        public static InterfaceResponse from(com.topo.discovery.model.NetworkInterface iface) {
            return InterfaceResponse.builder()
                    .name(iface.getName())
                    .macAddress(iface.getMacAddress())
                    .ipAddress(iface.getIpAddress())
                    .prefixLength(iface.getPrefixLength())
                    .description(iface.getDescription())
                    .adminStatus(iface.getAdminStatus())
                    .operStatus(iface.getOperStatus())
                    .speedMbps(iface.getSpeedMbps())
                    .vlan(iface.getVlan())
                    .build();
        }
    }
}
