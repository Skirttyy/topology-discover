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

/**
 * Construieste reprezentarea graf a topologiei folosind JGraphT (noduri =
 * Device, muchii = Link), apoi o serializeaza in formatul DTO consumat de
 * React Flow pe frontend.
 *
 * Folosirea unei librarii dedicate de grafuri (in loc de liste simple)
 * permite, in extensii viitoare ale tezei, algoritmi gata facuti: shortest
 * path intre doua device-uri, detectie de cicluri/bucle L2, componente
 * conexe (segmente izolate de retea), etc. - toate disponibile gratuit din
 * JGraphT odata ce graful e construit.
 */
@Service
@RequiredArgsConstructor
public class GraphBuilderService {

    private final DeviceRepository deviceRepository;
    private final LinkRepository linkRepository;

    public TopologyGraphResponse buildTopologyGraph() {
        List<Device> devices = deviceRepository.findAll();
        List<Link> links = linkRepository.findAll();

        Graph<Long, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
        devices.forEach(d -> graph.addVertex(d.getId()));

        List<TopologyGraphResponse.GraphNode> nodes = new ArrayList<>();
        for (Device device : devices) {
            nodes.add(TopologyGraphResponse.GraphNode.builder()
                    .id(String.valueOf(device.getId()))
                    .label(device.getHostname() != null ? device.getHostname() : device.getManagementIp())
                    .vendor(device.getVendor() != null ? device.getVendor().name() : "UNKNOWN")
                    .status(device.getStatus().name())
                    .managementIp(device.getManagementIp())
                    .model(device.getModel())
                    .build());
        }

        // deduplicam muchiile: Link e directional (local->remote) dar fizic
        // reprezinta o singura legatura; afisam o singura muchie per pereche
        Set<String> seenPairs = new HashSet<>();
        List<TopologyGraphResponse.GraphEdge> edges = new ArrayList<>();

        for (Link link : links) {
            if (link.getRemoteDevice() == null) {
                continue; // legatura cu vecin nerezolvat la un Device cunoscut - nu poate fi desenata ca muchie completa
            }

            Long localId = link.getLocalDevice().getId();
            Long remoteId = link.getRemoteDevice().getId();

            String pairKey = localId < remoteId
                    ? localId + "-" + remoteId
                    : remoteId + "-" + localId;

            if (seenPairs.contains(pairKey)) {
                continue;
            }
            seenPairs.add(pairKey);

            edges.add(TopologyGraphResponse.GraphEdge.builder()
                    .id("link-" + link.getId())
                    .source(String.valueOf(localId))
                    .target(String.valueOf(remoteId))
                    .sourceInterface(link.getLocalInterfaceName())
                    .targetInterface(link.getRemoteInterfaceName())
                    .discoverySource(link.getSource().name())
                    .build());

            if (graph.containsVertex(localId) && graph.containsVertex(remoteId)) {
                graph.addEdge(localId, remoteId);
            }
        }

        return TopologyGraphResponse.builder()
                .nodes(nodes)
                .edges(edges)
                .build();
    }
}
