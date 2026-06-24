package com.topo.discovery.controller;

import com.topo.discovery.dto.TopologyGraphResponse;
import com.topo.discovery.service.GraphBuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/topology")
@RequiredArgsConstructor
public class TopologyController {

    private final GraphBuilderService graphBuilderService;

    @GetMapping
    public TopologyGraphResponse getTopology() {
        return graphBuilderService.buildTopologyGraph();
    }
}
