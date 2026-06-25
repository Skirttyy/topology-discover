package com.topo.discovery.repository;

import com.topo.discovery.model.Device;
import com.topo.discovery.model.Link;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkRepository extends JpaRepository<Link, Long> {

    List<Link> findByLocalDevice(Device device);

    List<Link> findByRemoteDevice(Device device);

    void deleteByLocalDevice(Device device);

    boolean existsByLocalDeviceAndRemoteDevice(Device localDevice, Device remoteDevice);
}
