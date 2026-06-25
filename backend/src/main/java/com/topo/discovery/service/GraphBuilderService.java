package com.topo.discovery.service;

import com.topo.discovery.dto.TopologyGraphResponse;
import com.topo.discovery.model.Device;
import com.topo.discovery.model.Link;
import com.topo.discovery.repository.DeviceRepository;
import com.topo.discovery.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GraphBuilderService {

    private final DeviceRepository deviceRepository;
    private final LinkRepository linkRepository;

    public TopologyGraphResponse buildTopologyGraph() {
        // Copiem listele inainte de iterare — discovery poate modifica DB concurent
        List<Device> devices = new ArrayList<>(deviceRepository.findAll());
        List<Link>   links   = new ArrayList<>(linkRepository.findAll());

        List<TopologyGraphResponse.GraphNode> nodes = devices.stream()
                .map(GraphBuilderService::toNode)
                .toList();

        Set<String> seen  = new HashSet<>();
        List<TopologyGraphResponse.GraphEdge> edges = new ArrayList<>();

        for (Link link : links) {
            // Ambele capete trebuie sa existe
            if (link.getLocalDevice() == null || link.getRemoteDevice() == null) continue;

            Long localId  = link.getLocalDevice().getId();
            Long remoteId = link.getRemoteDevice().getId();
            if (localId == null || remoteId == null || localId.equals(remoteId)) continue;
            String key = Math.min(localId, remoteId) + "-" + Math.max(localId, remoteId);

            if (seen.contains(key)) continue;
            seen.add(key);

            edges.add(TopologyGraphResponse.GraphEdge.builder()
                    .id("link-" + link.getId())
                    .source(String.valueOf(localId))
                    .target(String.valueOf(remoteId))
                    .sourceInterface(link.getLocalInterfaceName())
                    .targetInterface(link.getRemoteInterfaceName())
                    .discoverySource(link.getSource().name())
                    .build());
        }

        return TopologyGraphResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    public static TopologyGraphResponse.GraphNode toNode(Device device) {
        return TopologyGraphResponse.GraphNode.builder()
                .id(String.valueOf(device.getId()))
                .label(device.getHostname() != null ? device.getHostname() : device.getManagementIp())
                .vendor(device.getVendor() != null ? device.getVendor().name() : "UNKNOWN")
                .status(device.getStatus().name())
                .managementIp(device.getManagementIp())
                .model(device.getModel())
                .osVersion(device.getOsVersion())
                .serialNumber(device.getSerialNumber())
                .sysDescr(device.getSysDescr())
                .lastError(device.getLastError())
                .build();
    }
}
