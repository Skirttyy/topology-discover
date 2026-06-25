import React, { useCallback, useEffect, useRef, useState } from 'react';
import ReactFlow, {
  Background, Controls, MiniMap,
  useNodesState, useEdgesState,
  useReactFlow, ReactFlowProvider,
} from 'reactflow';
import 'reactflow/dist/style.css';
import dagre from '@dagrejs/dagre';

import DeviceNode from './DeviceNode';
import DeviceDetailsPanel from './DeviceDetailsPanel';
import DiscoveryControls from './DiscoveryControls';
import DiscoveryStatusBar from './DiscoveryStatusBar';
import { getTopology } from '../api/client';

const nodeTypes = { device: DeviceNode };

const NODE_W = 220;
const NODE_H = 100;

// layout dagre: aranjeaza nodurile ierarhic top-down
function applyDagreLayout(nodes, edges) {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'TB', nodesep: 80, ranksep: 120, marginx: 40, marginy: 40 });

  nodes.forEach(n => g.setNode(n.id, { width: NODE_W, height: NODE_H }));
  edges.forEach(e => {
    if (e.source && e.target) g.setEdge(e.source, e.target);
  });

  dagre.layout(g);

  return nodes.map(n => {
    const pos = g.node(n.id);
    return { ...n, position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 } };
  });
}

function buildFlowNode(node, position) {
  return {
    id: node.id,
    type: 'device',
    position: position || { x: 0, y: 0 },
    data: {
      label: node.label,
      vendor: node.vendor,
      status: node.status,
      managementIp: node.managementIp,
      model: node.model,
      osVersion: node.osVersion,
      sysDescr: node.sysDescr,
    },
    style: { animation: 'nodeAppear 0.4s ease forwards' },
  };
}

function mapEdge(edge) {
  const sourceIf = edge.sourceInterface;
  const targetIf = edge.targetInterface;
  // scurteaza etichetele lungi (ex: "ge-0/0/1 ↔ ge-0/0/2")
  const trimIf = (s) => s && s.length > 20 ? s.substring(0, 20) + '…' : s;
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: sourceIf && targetIf ? `${trimIf(sourceIf)} ↔ ${trimIf(targetIf)}` : undefined,
    labelStyle: { fill: '#5A6275', fontSize: 10, fontFamily: "'JetBrains Mono', monospace" },
    labelBgStyle: { fill: '#0B0E14', fillOpacity: 0.85 },
    style: {
      stroke: edge.discoverySource === 'LLDP' ? '#3DDC84' : '#5A6275',
      strokeWidth: 1.8,
      strokeDasharray: edge.discoverySource === 'ARP_MAC_INFERENCE' ? '5 3' : undefined,
    },
    animated: false,
  };
}

function TopologyGraphInner() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [discoveryRunning, setDiscoveryRunning] = useState(false);
  const { fitView } = useReactFlow();

  const loadTopology = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getTopology()
      .then((graph) => {
        const rawNodes = (graph.nodes || []).map(n => buildFlowNode(n));
        const rawEdges = (graph.edges || []).map(mapEdge);
        const laid = rawNodes.length > 0 ? applyDagreLayout(rawNodes, rawEdges) : rawNodes;
        setNodes(laid);
        setEdges(rawEdges);
        // fit dupa ce React Flow randeaza
        setTimeout(() => fitView({ padding: 0.15, duration: 400 }), 50);
      })
      .catch((e) => setLoadError(e.message || 'Eroare la incarcare'))
      .finally(() => setLoading(false));
  }, [setNodes, setEdges, fitView]);

  useEffect(() => { loadTopology(); }, [loadTopology]);

  // NODE_DISCOVERED / NODE_UPDATED - adaugam/updatam nodul si re-rulam layout
  const handleNodeEvent = useCallback((node) => {
    if (!node?.id) return;
    setNodes(prev => {
      const exists = prev.find(n => n.id === node.id);
      if (exists) {
        return prev.map(n => n.id === node.id
          ? { ...n, data: { ...n.data, ...node, label: node.label || node.managementIp } }
          : n);
      }
      // nod nou - adaugam temporar, layout-ul se aplica in setEdges callback
      const newNode = buildFlowNode(
        { ...node, label: node.label || node.managementIp },
        { x: 600, y: 100 + prev.length * 150 }
      );
      return [...prev, newNode];
    });
  }, [setNodes]);

  // LINK_DISCOVERED - adaugam edge-ul si re-aplicam layout
  const handleLinkEvent = useCallback((edge) => {
    if (!edge?.id) return;
    setEdges(prev => {
      if (prev.find(e => e.id === edge.id)) return prev;
      const newEdge = { ...mapEdge(edge), animated: true };
      const nextEdges = [...prev, newEdge];
      // re-layout dupa ce adaugam edge
      setNodes(currentNodes => {
        if (currentNodes.length < 2) return currentNodes;
        return applyDagreLayout(currentNodes, nextEdges);
      });
      setTimeout(() => {
        setEdges(e => e.map(x => x.id === edge.id ? { ...x, animated: false } : x));
        fitView({ padding: 0.15, duration: 300 });
      }, 2000);
      return nextEdges;
    });
  }, [setEdges, setNodes, fitView]);

  const handleNodeClick = useCallback((_, node) => setSelectedId(node.id), []);

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
        fitViewOptions={{ padding: 0.15 }}
        proOptions={{ hideAttribution: true }}
        minZoom={0.1}
        maxZoom={2}
        defaultEdgeOptions={{ type: 'smoothstep' }}
      >
        <Background color="#1A1F2B" gap={28} size={1} />
        <Controls style={{ background: '#131720', border: '1px solid #252A35', borderRadius: 8 }} />
        <MiniMap
          nodeColor={n => {
            const s = n.data?.status;
            if (s === 'ACTIVE')      return '#3DDC84';
            if (s === 'ERROR')       return '#F2545B';
            if (s === 'UNREACHABLE') return '#F2A93B';
            if (s === 'POLLING')     return '#4D9DF2';
            return '#5A6275';
          }}
          maskColor="rgba(11,14,20,0.75)"
          style={{ background: '#131720', border: '1px solid #252A35', borderRadius: 8 }}
        />
      </ReactFlow>

      <DiscoveryControls
        onScanComplete={loadTopology}
        onRefresh={loadTopology}
        isRunning={discoveryRunning}
      />

      <DiscoveryStatusBar
        onNodeEvent={handleNodeEvent}
        onLinkEvent={handleLinkEvent}
        onCompleted={loadTopology}
        onStarted={() => setDiscoveryRunning(true)}
        onFinished={() => setDiscoveryRunning(false)}
      />

      {/* buton re-layout manual */}
      <button
        onClick={() => {
          setNodes(curr => applyDagreLayout(curr, edges));
          setTimeout(() => fitView({ padding: 0.15, duration: 400 }), 50);
        }}
        title="Re-aranjare automata layout"
        style={{
          position: 'absolute', bottom: 16, right: 16, zIndex: 10,
          background: '#131720', border: '1px solid #252A35',
          borderRadius: 6, color: '#8B93A3',
          padding: '6px 10px', fontSize: 11,
          cursor: 'pointer', fontFamily: "'JetBrains Mono', monospace",
        }}
      >
        ⊞ Auto-layout
      </button>

      {loading && <Overlay>Se incarca topologia...</Overlay>}
      {loadError && !loading && <Overlay error>Eroare: {loadError}</Overlay>}
      {isEmpty && !loadError && (
        <Overlay>Nicio topologie. Foloseste "+ Subnet scan" pentru a porni discovery-ul.</Overlay>
      )}

      {selectedId && (
        <DeviceDetailsPanel deviceId={selectedId} onClose={() => setSelectedId(null)} />
      )}
    </div>
  );
}

// ReactFlowProvider e necesar pentru useReactFlow()
export default function TopologyGraph() {
  return (
    <ReactFlowProvider>
      <TopologyGraphInner />
    </ReactFlowProvider>
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
