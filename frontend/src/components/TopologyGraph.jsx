import React, { useCallback, useEffect, useRef, useState } from 'react';
import ReactFlow, {
  Background, Controls, MiniMap,
  useNodesState, useEdgesState,
  addEdge,
} from 'reactflow';
import 'reactflow/dist/style.css';

import DeviceNode from './DeviceNode';
import DeviceDetailsPanel from './DeviceDetailsPanel';
import DiscoveryControls from './DiscoveryControls';
import DiscoveryStatusBar from './DiscoveryStatusBar';
import { getTopology } from '../api/client';

const nodeTypes = { device: DeviceNode };

// layout grid simplu - pozitionare initiala
const LAYOUT_OFFSET_X = 340; // evita suprapunerea cu panelul DiscoveryControls (latime ~300px + marja)
const LAYOUT_COL_W   = 280;
const LAYOUT_ROW_H   = 200;

function layoutNodes(graphNodes) {
  const COLS = Math.max(1, Math.ceil(Math.sqrt(graphNodes.length || 1)));
  return graphNodes.map((node, idx) => ({
    id: node.id,
    type: 'device',
    position: { x: LAYOUT_OFFSET_X + (idx % COLS) * LAYOUT_COL_W, y: Math.floor(idx / COLS) * LAYOUT_ROW_H },
    data: {
      label: node.label,
      vendor: node.vendor,
      status: node.status,
      managementIp: node.managementIp,
      model: node.model,
      osVersion: node.osVersion,
      sysDescr: node.sysDescr,
    },
    // animatie de intrare
    style: { animation: 'nodeAppear 0.4s ease forwards' },
  }));
}

function mapEdge(edge) {
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.sourceInterface && edge.targetInterface
      ? `${edge.sourceInterface} ↔ ${edge.targetInterface}` : undefined,
    labelStyle: { fill: '#5A6275', fontSize: 10, fontFamily: "'JetBrains Mono', monospace" },
    labelBgStyle: { fill: '#0B0E14', fillOpacity: 0.85 },
    style: {
      stroke: edge.discoverySource === 'LLDP' ? '#3DDC84' : '#5A6275',
      strokeWidth: 1.8,
      strokeDasharray: edge.discoverySource === 'SNMP_ARP' ? '5 3' : undefined,
    },
    animated: false,
  };
}

export default function TopologyGraph() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);

  // ref cu nodesMap pentru update rapid fara re-layout
  const nodesMapRef = useRef({});

  const loadTopology = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getTopology()
      .then((graph) => {
        const mapped = layoutNodes(graph.nodes || []);
        // rebuildam nodesMap
        nodesMapRef.current = {};
        mapped.forEach(n => { nodesMapRef.current[n.id] = n; });
        setNodes(mapped);
        setEdges((graph.edges || []).map(mapEdge));
      })
      .catch((e) => setLoadError(e.message || 'Eroare la incarcare'))
      .finally(() => setLoading(false));
  }, [setNodes, setEdges]);

  useEffect(() => { loadTopology(); }, [loadTopology]);

  // handler pentru NODE_DISCOVERED / NODE_UPDATED din WebSocket
  const handleNodeEvent = useCallback((node, type) => {
    if (!node?.id) return;
    setNodes(prev => {
      const existing = prev.find(n => n.id === node.id);
      if (existing) {
        // update date fara sa schimbam pozitia
        return prev.map(n => n.id === node.id
          ? { ...n, data: { ...n.data, ...node, label: node.label || node.managementIp } }
          : n);
      } else {
        // nod nou - il adaugam cu animatie
        const idx = prev.length;
        const COLS = Math.max(1, Math.ceil(Math.sqrt(idx + 1)));
        const newNode = {
          id: node.id,
          type: 'device',
          position: { x: LAYOUT_OFFSET_X + (idx % COLS) * LAYOUT_COL_W, y: Math.floor(idx / COLS) * LAYOUT_ROW_H },
          data: { ...node, label: node.label || node.managementIp },
          style: { animation: 'nodeAppear 0.5s cubic-bezier(0.34,1.56,0.64,1) forwards' },
        };
        nodesMapRef.current[node.id] = newNode;
        return [...prev, newNode];
      }
    });
  }, [setNodes]);

  // handler pentru LINK_DISCOVERED din WebSocket
  const handleLinkEvent = useCallback((edge) => {
    if (!edge?.id) return;
    setEdges(prev => {
      if (prev.find(e => e.id === edge.id)) return prev;
      return [...prev, { ...mapEdge(edge), animated: true }];
    });
    // dupa 2s scoatem animatia de pe link
    setTimeout(() => {
      setEdges(prev => prev.map(e => e.id === edge.id ? { ...e, animated: false } : e));
    }, 2000);
  }, [setEdges]);

  const handleNodeClick = useCallback((_, node) => {
    setSelectedId(node.id);
  }, []);

  const isEmpty = nodes.length === 0 && !loading;

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative', background: '#0B0E14' }}>
      <style>{`
        @keyframes nodeAppear {
          from { opacity: 0; transform: scale(0.7) translateY(10px); }
          to   { opacity: 1; transform: scale(1) translateY(0); }
        }
      `}</style>

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        onPaneClick={() => setSelectedId(null)}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        proOptions={{ hideAttribution: true }}
        minZoom={0.2}
        maxZoom={2}
      >
        <Background color="#1A1F2B" gap={28} size={1} />
        <Controls style={{
          background: '#131720',
          border: '1px solid #252A35',
          borderRadius: 8,
        }} />
        <MiniMap
          nodeColor={n => {
            const s = n.data?.status;
            if (s === 'ACTIVE') return '#3DDC84';
            if (s === 'ERROR') return '#F2545B';
            if (s === 'UNREACHABLE') return '#F2A93B';
            if (s === 'POLLING') return '#4D9DF2';
            return '#5A6275';
          }}
          maskColor="rgba(11,14,20,0.75)"
          style={{ background: '#131720', border: '1px solid #252A35', borderRadius: 8 }}
        />
      </ReactFlow>

      <DiscoveryControls onScanComplete={loadTopology} onRefresh={loadTopology} />

      <DiscoveryStatusBar
        onNodeEvent={handleNodeEvent}
        onLinkEvent={handleLinkEvent}
        onCompleted={loadTopology}
      />

      {loading && <Overlay>Se incarca topologia...</Overlay>}
      {loadError && !loading && <Overlay error>Eroare: {loadError}</Overlay>}
      {isEmpty && !loadError && (
        <Overlay>
          Nicio topologie. Foloseste "+ Subnet scan" pentru a porni discovery-ul.
        </Overlay>
      )}

      {selectedId && (
        <DeviceDetailsPanel
          deviceId={selectedId}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  );
}

function Overlay({ children, error }) {
  return (
    <div style={{
      position: 'absolute', top: '50%', left: '50%',
      transform: 'translate(-50%, -50%)',
      color: error ? '#F2545B' : '#5A6275',
      fontFamily: "'JetBrains Mono', monospace",
      fontSize: 13, textAlign: 'center',
      pointerEvents: 'none', maxWidth: 360,
    }}>
      {children}
    </div>
  );
}
