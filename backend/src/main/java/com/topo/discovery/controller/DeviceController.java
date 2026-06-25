package com.topo.discovery.controller;

import com.topo.discovery.dto.CreateDeviceRequest;
import com.topo.discovery.dto.DeviceDetailsResponse;
import com.topo.discovery.model.Device;
import com.topo.discovery.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public List<DeviceDetailsResponse> listDevices() {
        return deviceService.findAll().stream()
                .map(DeviceDetailsResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public DeviceDetailsResponse getDevice(@PathVariable Long id) {
        return DeviceDetailsResponse.from(deviceService.findById(id));
    }

    @PostMapping
    public ResponseEntity<DeviceDetailsResponse> createDevice(@Valid @RequestBody CreateDeviceRequest request) {
        Device device = deviceService.createSeedDevice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(DeviceDetailsResponse.from(device));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Integer>> deleteAllDevices() {
        int count = deviceService.deleteAll();
        return ResponseEntity.ok(Map.of("deletedDevices", count));
    }
}
