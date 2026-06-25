import React, { useCallback, useEffect, useRef, useState } from 'react';
import ReactFlow, {
  Background, Controls, MiniMap,
  useNodesState, useEdgesState,
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
const NODE_H = 110;

function applyDagreLayout(nodes, edges) {
  if (nodes.length === 0) return nodes;
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'TB', nodesep: 100, ranksep: 140, marginx: 60, marginy: 60 });

  nodes.forEach(n => g.setNode(n.id, { width: NODE_W, height: NODE_H }));
  // adaugam doar edge-urile unde ambele capete exista ca noduri
  const nodeIds = new Set(nodes.map(n => n.id));
  edges.forEach(e => {
    if (nodeIds.has(e.source) && nodeIds.has(e.target)) {
      g.setEdge(e.source, e.target);
    }
  });

  dagre.layout(g);

  return nodes.map(n => {
    const pos = g.node(n.id);
    // daca dagre nu a putut pozitiona nodul (izolat), pastreaza pozitia existenta
    if (!pos) return n;
    return { ...n, position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 } };
  });
}

function buildFlowNode(node) {
  return {
    id: String(node.id),
    type: 'device',
    position: { x: 0, y: 0 }, // va fi suprascris de dagre
    data: {
      label:        node.label,
      vendor:       node.vendor,
      status:       node.status,
      managementIp: node.managementIp,
      model:        node.model,
      osVersion:    node.osVersion,
      sysDescr:     node.sysDescr,
    },
    style: { animation: 'nodeAppear 0.4s ease forwards' },
  };
}

function trimIf(s) {
  if (!s) return s;
  // scoatem numerele de index SNMP din prefix (ex: "529 :: ge-0/0/1" -> "ge-0/0/1")
  const clean = s.replace(/^\d+\s*::\s*/, '').trim();
  return clean.length > 18 ? clean.substring(0, 18) + '…' : clean;
}

function mapEdge(edge) {
  return {
    id: String(edge.id),
    source: String(edge.source),
    target: String(edge.target),
    label: edge.sourceInterface && edge.targetInterface
      ? `${trimIf(edge.sourceInterface)} ↔ ${trimIf(edge.targetInterface)}` : undefined,
    labelStyle: { fill: '#5A6275', fontSize: 10, fontFamily: "'JetBrains Mono', monospace" },
    labelBgStyle: { fill: '#0B0E14', fillOpacity: 0.85 },
    style: {
      stroke: edge.discoverySource === 'LLDP' ? '#3DDC84' : '#5A6275',
      strokeWidth: 1.8,
      strokeDasharray: edge.discoverySource === 'ARP_MAC_INFERENCE' ? '5 3' : undefined,
    },
    type: 'smoothstep',
    animated: false,
  };
}

export default function TopologyGraph() {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedId,       setSelectedId]       = useState(null);
  const [loading,          setLoading]          = useState(true);
  const [loadError,        setLoadError]        = useState(null);
  const [discoveryRunning, setDiscoveryRunning] = useState(false);

  // instanta React Flow obtinuta prin onInit - singura metoda fiabila de a apela fitView
  const rfRef = useRef(null);

  const doFitView = useCallback(() => {
    setTimeout(() => rfRef.current?.fitView({ padding: 0.15, duration: 400 }), 80);
  }, []);

  const runLayout = useCallback((currentNodes, currentEdges) => {
    const laid = applyDagreLayout(currentNodes, currentEdges);
    setNodes(laid);
    doFitView();
  }, [setNodes, doFitView]);

  const loadTopology = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getTopology()
      .then((graph) => {
        const rawNodes = (graph.nodes || []).map(buildFlowNode);
        const rawEdges = (graph.edges || []).map(mapEdge);
        const laid = applyDagreLayout(rawNodes, rawEdges);
        setNodes(laid);
        setEdges(rawEdges);
        doFitView();
      })
      .catch((e) => setLoadError(e.message || 'Eroare la incarcare'))
      .finally(() => setLoading(false));
  }, [setNodes, setEdges, doFitView]);

  useEffect(() => { loadTopology(); }, [loadTopology]);

  const handleNodeEvent = useCallback((node) => {
    if (!node?.id) return;
    const nodeId = String(node.id);
    setNodes(prev => {
      const exists = prev.find(n => n.id === nodeId);
      if (exists) {
        return prev.map(n => n.id === nodeId
          ? { ...n, data: { ...n.data, ...node, label: node.label || node.managementIp } }
          : n);
      }
      // nod nou - pozitie temporara, utilizatorul poate da Auto-layout dupa
      const newNode = {
        ...buildFlowNode({ ...node, label: node.label || node.managementIp }),
        id: nodeId,
        position: { x: 100 + prev.length * 260, y: 80 },
      };
      return [...prev, newNode];
    });
  }, [setNodes]);

  const handleLinkEvent = useCallback((edge) => {
    if (!edge?.id) return;
    setEdges(prev => {
      if (prev.find(e => e.id === String(edge.id))) return prev;
      return [...prev, { ...mapEdge(edge), animated: true }];
    });
    setTimeout(() => {
      setEdges(prev => prev.map(e =>
        e.id === String(edge.id) ? { ...e, animated: false } : e
      ));
    }, 2000);
  }, [setEdges]);

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
        onInit={(instance) => { rfRef.current = instance; }}
        fitView
        fitViewOptions={{ padding: 0.15 }}
        proOptions={{ hideAttribution: true }}
        minZoom={0.1}
        maxZoom={2}
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

      {/* buton auto-layout manual - util dupa ce ai tras nodurile */}
      <button
        onClick={() => runLayout(nodes, edges)}
        title="Re-aranjare ierarhica automata"
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
