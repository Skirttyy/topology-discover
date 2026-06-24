import React, { useCallback, useEffect, useMemo, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
} from 'reactflow';
import 'reactflow/dist/style.css';

import DeviceNode from './DeviceNode';
import DeviceDetailsPanel from './DeviceDetailsPanel';
import DiscoveryControls from './DiscoveryControls';
import DiscoveryStatusBar from './DiscoveryStatusBar';
import { getTopology } from '../api/client';

const nodeTypes = { device: DeviceNode };

// layout simplu in grid - pozitionare initiala determinista, fara
// dependinta de o librarie externa de layout automat (suficient pt teza;
// se poate extinde ulterior cu un algoritm force-directed / dagre)
function layoutNodes(graphNodes) {
  const COLS = Math.ceil(Math.sqrt(graphNodes.length || 1));
  const SPACING_X = 240;
  const SPACING_Y = 160;

  return graphNodes.map((node, idx) => ({
    id: node.id,
    type: 'device',
    position: {
      x: (idx % COLS) * SPACING_X,
      y: Math.floor(idx / COLS) * SPACING_Y,
    },
    data: {
      label: node.label,
      vendor: node.vendor,
      status: node.status,
      managementIp: node.managementIp,
      model: node.model,
    },
  }));
}

function mapEdges(graphEdges) {
  return graphEdges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.sourceInterface && edge.targetInterface
      ? `${edge.sourceInterface} ↔ ${edge.targetInterface}`
      : undefined,
    labelStyle: { fill: 'var(--text-tertiary)', fontSize: 10, fontFamily: 'var(--font-mono)' },
    labelBgStyle: { fill: 'var(--bg-app)', fillOpacity: 0.8 },
    style: {
      stroke: edge.discoverySource === 'LLDP' ? '#3DDC84' : '#5A6275',
      strokeWidth: 1.5,
      strokeDasharray: edge.discoverySource === 'ARP_MAC_INFERENCE' ? '4 3' : undefined,
    },
    animated: false,
  }));
}

export default function TopologyGraph() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);

  const refresh = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getTopology()
      .then((graph) => {
        setNodes(layoutNodes(graph.nodes || []));
        setEdges(mapEdges(graph.edges || []));
      })
      .catch((e) => setLoadError(e.message || 'Eroare la incarcarea topologiei'))
      .finally(() => setLoading(false));
  }, [setNodes, setEdges]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const handleNodeClick = useCallback((_event, node) => {
    setSelectedDeviceId(node.id);
  }, []);

  const handleDiscoveryEvent = useCallback((event) => {
    if (event.type === 'COMPLETED') {
      refresh();
    }
  }, [refresh]);

  const isEmpty = useMemo(() => nodes.length === 0 && !loading, [nodes, loading]);

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative', background: 'var(--bg-app)' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        nodeTypes={nodeTypes}
        fitView
        proOptions={{ hideAttribution: true }}
      >
        <Background color="#1A1F2B" gap={24} size={1} />
        <Controls
          style={{
            button: { background: 'var(--bg-panel)', color: 'var(--text-primary)', borderColor: 'var(--border-subtle)' },
          }}
        />
        <MiniMap
          nodeColor={(n) => {
            const status = n.data?.status;
            if (status === 'ACTIVE') return '#3DDC84';
            if (status === 'ERROR') return '#F2545B';
            if (status === 'UNREACHABLE') return '#F2A93B';
            return '#5A6275';
          }}
          maskColor="rgba(11,14,20,0.7)"
          style={{ background: 'var(--bg-panel)' }}
        />
      </ReactFlow>

      <DiscoveryControls onScanComplete={refresh} onRefresh={refresh} />
      <DiscoveryStatusBar onDiscoveryEvent={handleDiscoveryEvent} />

      {loading && (
        <CenterMessage>Se incarca topologia...</CenterMessage>
      )}

      {loadError && !loading && (
        <CenterMessage error>Nu am putut incarca topologia: {loadError}</CenterMessage>
      )}

      {isEmpty && !loadError && (
        <CenterMessage>
          Nicio topologie inca. Foloseste "Scaneaza subnet" pentru a porni discovery-ul.
        </CenterMessage>
      )}

      {selectedDeviceId && (
        <DeviceDetailsPanel
          deviceId={selectedDeviceId}
          onClose={() => setSelectedDeviceId(null)}
        />
      )}
    </div>
  );
}

function CenterMessage({ children, error }) {
  return (
    <div style={{
      position: 'absolute',
      top: '50%',
      left: '50%',
      transform: 'translate(-50%, -50%)',
      color: error ? 'var(--accent-error)' : 'var(--text-tertiary)',
      fontFamily: 'var(--font-mono)',
      fontSize: 13,
      textAlign: 'center',
      pointerEvents: 'none',
      maxWidth: 360,
    }}>
      {children}
    </div>
  );
}
