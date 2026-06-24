package com.topo.discovery.repository;

import com.topo.discovery.model.Device;
import com.topo.discovery.model.NetworkInterface;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NetworkInterfaceRepository extends JpaRepository<NetworkInterface, Long> {

    List<NetworkInterface> findByDevice(Device device);

    Optional<NetworkInterface> findByDeviceAndName(Device device, String name);

    Optional<NetworkInterface> findByMacAddress(String macAddress);
}
