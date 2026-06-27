package com.topo.discovery.controller;

import com.topo.discovery.dto.ScanSubnetRequest;
import com.topo.discovery.model.Device;
import com.topo.discovery.service.DeviceService;
import com.topo.discovery.service.DiscoveryEngineService;
import com.topo.discovery.service.SubnetScannerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoint-uri pentru a porni procesul de discovery:
 * - scanare subnet (gaseste device-uri vii + le adauga ca seed)
 * - pornire BFS de la device-uri seed existente
 * - status al ultimei rulari (folosit ca fallback de polling, in plus fata de WebSocket)
 */
@RestController
@RequestMapping("/api/discovery")
@RequiredArgsConstructor
public class DiscoveryController {

    private final SubnetScannerService subnetScannerService;
    private final DeviceService deviceService;
    private final DiscoveryEngineService discoveryEngineService;

    /**
     * Scaneaza un subnet (CIDR), gaseste device-urile vii pe portul SSH,
     * le adauga ca seed devices si, daca autoStartDiscovery=true (default),
     * porneste imediat discovery-ul BFS de pe ele - inclusiv bootstrap
     * config (activare SNMP+LLDP).
     */
    @PostMapping("/scan-subnet")
    public ResponseEntity<Map<String, Object>> scanSubnet(@Valid @RequestBody ScanSubnetRequest request) {
        List<String> subnets = request.getAllSubnets();
        List<String> liveHosts = subnetScannerService.scanForLiveHosts(subnets);

        List<Long> seedIds = liveHosts.stream()
                .map(ip -> deviceService.createSeedDeviceIfAbsent(
                        ip, request.getVendor(), request.getSshUsername(),
                        request.getSshPassword(), request.getSnmpCommunity()))
                .map(Device::getId)
                .toList();

        if (request.isAutoStartDiscovery() && !seedIds.isEmpty()) {
            discoveryEngineService.runDiscoveryAsync(seedIds);
        }

        return ResponseEntity.ok(Map.of(
                "subnetsScanned", subnets,
                "liveHostsFound", liveHosts.size(),
                "liveHosts", liveHosts,
                "seedDeviceIds", seedIds,
                "discoveryStarted", request.isAutoStartDiscovery() && !seedIds.isEmpty()
        ));
    }

    /** Porneste (sau reporneste) discovery BFS de la o lista explicita de device-uri seed deja existente. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runDiscovery(@RequestBody List<Long> seedDeviceIds) {
        if (seedDeviceIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Trebuie cel putin un seed device id"));
        }
        discoveryEngineService.runDiscoveryAsync(seedDeviceIds);
        return ResponseEntity.accepted().body(Map.of("started", true, "seedDeviceIds", seedDeviceIds));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopDiscovery() {
        boolean wasStopped = discoveryEngineService.requestStop();
        return ResponseEntity.ok(Map.of("stopped", wasStopped));
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return discoveryEngineService.getLastRunStatus();
    }
}
