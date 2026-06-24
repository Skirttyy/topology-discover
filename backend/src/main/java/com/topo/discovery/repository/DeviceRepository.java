package com.topo.discovery.repository;

import com.topo.discovery.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByManagementIp(String managementIp);

    boolean existsByManagementIp(String managementIp);
}
