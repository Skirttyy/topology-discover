import React, { useCallback, useEffect, useRef, useState } from 'react';
import ReactFlow, {
  Background, Controls, MiniMap,
  applyNodeChanges, applyEdgeChanges,
} from 'reactflow';
import 'reactflow/dist/style.css';
import dagre from '@dagrejs/dagre';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

import DeviceNode from './components/DeviceNode';
import LabeledEdge from './components/LabeledEdge';
import DeviceDetailsPanel from './components/DeviceDetailsPanel';
import { getTopology, scanSubnet, stopDiscovery, resetTopology, getDiscoveryStatus, WS_BASE_URL } from './api/client';

// ─── React Flow types ───────────────────────────────────────────────────────
const nodeTypes = { device: DeviceNode };
const edgeTypes = { labeled: LabeledEdge };

// ─── Dagre layout ───────────────────────────────────────────────────────────
const NW = 224, NH = 108;

// ─── Detectie rol in topologie dupa conventii de naming ─────────────────────
// Ordinea conteaza: keywords mai specifice mai intai
const ROLE_KEYWORDS = [
  ['internet', 0], ['wan',      0], ['tunnel',   0], ['firewall',  0], ['fw', 0], ['uplink', 0],
  ['core',     1], ['border',   1], ['gw',        1], ['gateway',   1],
  ['spine',    2], ['dist',     2], ['agg',        2],
  ['leaf',     3], ['access',   3], ['tor',        3], ['sw',        3],
  ['server',   4], ['host',     4], ['pc',         4],
];

/**
 * Detecteaza rank-ul unui nod din label/model/vendor.
 * Returneaza null pentru placeholder-uri cu rol necunoscut
 * (vor fi rezolvate in functie de vecinii lor).
 */
function detectNodeRank(node) {
  const label  = (node.data?.label || '').toLowerCase();
  const model  = (node.data?.model || '').toLowerCase();
  const ip     = (node.data?.managementIp || '').toLowerCase();
  const vendor = node.data?.vendor || 'UNKNOWN';

  // Construim un string de cautare care include atat label cat si ip fara prefix lldp:
  // Astfel "lldp:wan01-node.ch.md" contribuie "wan01-node.ch.md" la cautare
  const cleanIp   = ip.replace(/^lldp:/, '');
  const searchStr = `${label} ${cleanIp}`; // ex: "wan01-node.ch.md lldp:wan01-node.ch.md"

  // Verificam keywords (valabil pentru orice nod, inclusiv placeholder)
  for (const [kw, rank] of ROLE_KEYWORDS) {
    if (searchStr.includes(kw)) return rank;
  }

  // Placeholder cu rol nedetectabil — rank calculat din vecini in pasul 2
  if (ip.startsWith('lldp:')) return null;

  // Detectie din model pentru device-uri reale fara keyword in hostname
  if (model.includes('vmx') || model.includes('-mx') || model.includes('ptx')) return 1;
  if (model.includes('qfx') || model.includes('ex') || model.includes('arista')) return 3;
  if (vendor === 'MIKROTIK') return 0;

  return 4; // necunoscut dar real
}

/**
 * Layout ierarhic piramidal in doua pasuri:
 *
 * Pasul 1: Asignam rank-uri nodurilor cu rol detectabil (din label/model/vendor).
 * Pasul 2: Placeholder-urile cu rol necunoscut primesc rank = min(rank vecini) - 1
 *          (presupunem ca e un device upstream/WAN fata de cel care l-a descoperit).
 *          Daca nu au vecini cunoscuti, merg la nivelul cel mai de sus + 1.
 */
function hierarchicalLayout(nodes, edges) {
  if (!nodes.length) return nodes;

  const LEVEL_HEIGHT = 185;
  const NODE_GAP     = 65;
  const START_Y      = 60;

  // ── Pasul 1: rank-uri din detectie directa ──────────────────────────────
  const rankMap = new Map();
  nodes.forEach(n => {
    const r = detectNodeRank(n);
    if (r !== null) rankMap.set(n.id, r);
  });

  // ── Pasul 2: rank-uri pentru placeholder-uri fara rol detectat ──────────
  nodes.forEach(n => {
    if (rankMap.has(n.id)) return;

    // gasim rank-urile vecinilor deja rezolvati
    const neighborRanks = edges
      .filter(e => e.source === n.id || e.target === n.id)
      .map(e => rankMap.get(e.source === n.id ? e.target : e.source))
      .filter(r => r !== undefined && r !== null);

    if (neighborRanks.length > 0) {
      // Plasam placeholder-ul un nivel DEASUPRA vecinului cu cel mai mic rank.
      // Rationale: placeholder-ul a fost descoperit prin LLDP de catre acel vecin,
      // deci e probabil un device upstream (WAN, core deasupra unui spine, etc.)
      const minNeighborRank = Math.min(...neighborRanks);
      rankMap.set(n.id, Math.max(0, minNeighborRank - 1));
    } else {
      // Placeholder complet izolat (fara edges) — sus (rank 0)
      rankMap.set(n.id, 0);
    }
  });

  // ── Grupare pe nivel ────────────────────────────────────────────────────
  const byRank = new Map();
  nodes.forEach(n => {
    const r = rankMap.get(n.id) ?? 4;
    if (!byRank.has(r)) byRank.set(r, []);
    byRank.get(r).push(n);
  });
  const sortedRanks = [...byRank.keys()].sort((a, b) => a - b);
  const levels = sortedRanks.map(r => byRank.get(r));

  // ── Reducere incrucisari: ordonare barycenter ───────────────────────────
  // Construim lista de vecini pentru fiecare nod
  const adj = new Map(nodes.map(n => [n.id, []]));
  edges.forEach(e => {
    if (adj.has(e.source) && adj.has(e.target)) {
      adj.get(e.source).push(e.target);
      adj.get(e.target).push(e.source);
    }
  });

  // index-ul curent al fiecarui nod in nivelul sau (pentru calcul barycenter)
  const idxOf = new Map();
  levels.forEach(lvl => lvl.forEach((n, i) => idxOf.set(n.id, i)));

  // Sorteaza un nivel dupa pozitia medie a vecinilor din nivelul de referinta
  const orderByBarycenter = (lvl) => {
    const score = new Map();
    lvl.forEach(n => {
      const neigh = adj.get(n.id) || [];
      const positions = neigh.map(id => idxOf.get(id)).filter(v => v !== undefined);
      score.set(n.id, positions.length ? positions.reduce((a, b) => a + b, 0) / positions.length : idxOf.get(n.id));
    });
    lvl.sort((a, b) => score.get(a.id) - score.get(b.id));
    lvl.forEach((n, i) => idxOf.set(n.id, i));
  };

  // Cateva treceri sus→jos si jos→sus pentru a stabiliza ordinea
  for (let pass = 0; pass < 4; pass++) {
    if (pass % 2 === 0) for (let i = 1; i < levels.length; i++) orderByBarycenter(levels[i]);
    else                for (let i = levels.length - 2; i >= 0; i--) orderByBarycenter(levels[i]);
  }

  // ── Pozitionare finala (centrata pe latimea maxima) ─────────────────────
  let maxW = 0;
  levels.forEach(lvl => { const w = lvl.length * (NW + NODE_GAP) - NODE_GAP; if (w > maxW) maxW = w; });

  const result = [];
  levels.forEach((lvl, levelIdx) => {
    const lvlW   = lvl.length * (NW + NODE_GAP) - NODE_GAP;
    const startX = (maxW - lvlW) / 2 + 80;
    const y      = START_Y + levelIdx * LEVEL_HEIGHT;
    lvl.forEach((n, i) => {
      result.push({ ...n, position: { x: startX + i * (NW + NODE_GAP), y } });
    });
  });

  return result;
}

// dagreLayout e aliasul public al hierarchicalLayout
const dagreLayout = hierarchicalLayout;

// ─── Helpers ────────────────────────────────────────────────────────────────
function mkNode(raw, position) {
  return {
    id: String(raw.id),
    type: 'device',
    position: position ?? { x: 0, y: 0 },
    data: {
      label:        raw.label || raw.managementIp,
      vendor:       raw.vendor,
      status:       raw.status,
      managementIp: raw.managementIp,
      model:        raw.model,
      osVersion:    raw.osVersion,
      sysDescr:     raw.sysDescr,
      lastError:    raw.lastError,
    },
  };
}

// Backend-ul trimite deja nume de interfata curate (sau null cand nu e sigur).
// Frontend-ul doar afiseaza: trim + scurtare pentru a incapea pe edge.
function bestIfName(s) {
  if (!s) return null;
  const t = String(s).trim();
  if (!t) return null;
  return t.length > 16 ? t.slice(0, 16) + '…' : t;
}

function mkEdge(raw) {
  const si = bestIfName(raw.sourceInterface);
  const ti = bestIfName(raw.targetInterface);
  return {
    id:     String(raw.id),
    source: String(raw.source),
    target: String(raw.target),
    type:   'labeled',   // floating edge — isi calculeaza singur punctele pe perimetru
    data:   { sourceLabel: si || null, targetLabel: ti || null },
    style:  { stroke: '#3DDC84', strokeWidth: 1.7 },
    animated: false,
  };
}

// ─── App ────────────────────────────────────────────────────────────────────
export default function App() {
  const [nodes,      setNodes]      = useState([]);
  const [edges,      setEdges]      = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [loading,    setLoading]    = useState(true);
  const [topoErr,    setTopoErr]    = useState(null);
  const [running,    setRunning]    = useState(false);
  const [connected,  setConnected]  = useState(false);
  const [messages,   setMessages]   = useState([]);
  const [errors,     setErrors]     = useState([]);  // erori backend distincte
  const [formOpen,   setFormOpen]   = useState(false);
  const [scanning,   setScanning]   = useState(false);
  const [scanRes,    setScanRes]    = useState(null);
  const [scanErr,    setScanErr]    = useState(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const [form, setForm] = useState({
    subnets: '192.168.1.0/24',  // unul sau mai multe CIDR-uri, un subnet pe linie (sau separate prin virgula)
    sshUsername: '', sshPassword: '', snmpCommunity: '',
  });

  // React Flow instance (pentru fitView)
  const rfRef          = useRef(null);
  // Pozitiile "acasa" ale nodurilor dupa dagre — pentru spring-back
  const homePositions  = useRef({});
  // Ref-uri pentru closure-safe access in WebSocket handler
  const edgesRef = useRef(edges);
  useEffect(() => { edgesRef.current = edges; }, [edges]);

  // ── React Flow change handlers ──────────────────────────────────────────
  const onNodesChange = useCallback(ch => setNodes(ns => applyNodeChanges(ch, ns)), []);
  const onEdgesChange = useCallback(ch => setEdges(es => applyEdgeChanges(ch, es)), []);

  // ── Helpers ─────────────────────────────────────────────────────────────
  const addMsg = useCallback((text, color = '#8B93A3') => {
    setMessages(p => [...p.slice(-7), { text, color, id: Date.now() + Math.random() }]);
  }, []);

  const addErr = useCallback((text) => {
    setErrors(p => [...p.slice(-4), { text, id: Date.now() + Math.random(), ts: new Date().toLocaleTimeString('ro-RO') }]);
  }, []);

  const fitView = useCallback(() => {
    setTimeout(() => rfRef.current?.fitView({ padding: 0.12, duration: 350 }), 80);
  }, []);

  const applyLayout = useCallback(() => {
    setNodes(curr => {
      const laid = dagreLayout(curr, edgesRef.current);
      laid.forEach(n => { homePositions.current[n.id] = { ...n.position }; });
      return laid;
    });
    fitView();
  }, [fitView]);

  // Spring-back: cand userul elibereaza un nod, il animam inapoi la pozitia dagre
  // Retinem frame ID pentru cleanup (evita memory leak daca nodul e sters in timpul animatiei)
  const animFrames = useRef({});
  const onNodeDragStop = useCallback((_, node) => {
    const home = homePositions.current[node.id];
    if (!home) return;

    // Anuleaza animatia anterioara pe acelasi nod (daca exista)
    if (animFrames.current[node.id]) cancelAnimationFrame(animFrames.current[node.id]);

    const startX = node.position.x;
    const startY = node.position.y;
    const startTime = performance.now();
    const duration  = 450;

    const step = (now) => {
      const t    = Math.min((now - startTime) / duration, 1);
      const ease = 1 - Math.pow(1 - t, 3);
      setNodes(prev => prev.map(n =>
        n.id === node.id
          ? { ...n, position: { x: startX + (home.x - startX) * ease, y: startY + (home.y - startY) * ease } }
          : n
      ));
      if (t < 1) {
        animFrames.current[node.id] = requestAnimationFrame(step);
      } else {
        delete animFrames.current[node.id];
      }
    };
    animFrames.current[node.id] = requestAnimationFrame(step);
  }, [setNodes]);

  // ── Load topology ────────────────────────────────────────────────────────
  const loadTopology = useCallback(() => {
    setLoading(true);
    setTopoErr(null);
    Promise.all([getTopology(), getDiscoveryStatus()])
      .then(([graph, status]) => {
        // sincronizam starea running cu backend-ul (persista dupa refresh)
        setRunning(status.running === true);

        const fn = (graph.nodes || []).map(n => mkNode(n));
        const fe = (graph.edges || []).map(mkEdge);
        const laid = dagreLayout(fn, fe);
        laid.forEach(n => { homePositions.current[n.id] = { ...n.position }; });
        edgesRef.current = fe;
        setNodes(laid);
        setEdges(fe);
        fitView();
      })
      .catch(e => setTopoErr(e.message || 'Eroare'))
      .finally(() => setLoading(false));
  }, [fitView]);

  useEffect(() => { loadTopology(); }, [loadTopology]);

  // ── WebSocket ────────────────────────────────────────────────────────────
  // Folosim un ref pentru handler ca sa evitam re-subscribe la fiecare render
  const wsHandlerRef = useRef(null);
  wsHandlerRef.current = useCallback((p) => {
    switch (p.type) {
      case 'NODE_DISCOVERED':
        setRunning(true);
        addMsg(`+ ${p.node?.label || p.node?.managementIp}`, '#3DDC84');
        setNodes(prev => {
          const id = String(p.node?.id);
          if (!id || prev.find(n => n.id === id)) return prev;
          return [...prev, mkNode(p.node, { x: 260 + prev.length * 260, y: 60 })];
        });
        break;

      case 'NODE_UPDATED': {
        const nd = p.node;
        const hasErr = nd?.lastError;
        addMsg(
          `↻ ${nd?.label || nd?.managementIp}${hasErr ? ' ⚠' : ''}`,
          hasErr ? '#F2A93B' : '#4D9DF2'
        );
        setNodes(prev => prev.map(n =>
          n.id === String(nd?.id)
            ? { ...n, data: { ...n.data, ...nd, label: nd?.label || nd?.managementIp } }
            : n
        ));
        break;
      }

      case 'LINK_DISCOVERED': {
        const eid = String(p.edge?.id);
        const src = String(p.edge?.source);
        const tgt = String(p.edge?.target);
        // afisam interfetele, nu device ID-urile (care sunt numere din DB, fara sens pentru user)
        const si = bestIfName(p.edge?.sourceInterface);
        const ti = bestIfName(p.edge?.targetInterface);
        let linkLabel;
        if (si && ti) linkLabel = `${si} ↔ ${ti}`;
        else if (si)  linkLabel = si;
        else if (ti)  linkLabel = ti;
        else          linkLabel = 'link';
        addMsg(`⟷ ${linkLabel}`, '#7C8CF8');
        setEdges(prev => {
          if (!eid) return prev;
          // deduplicare pe pereche (bonding/LAG poate trimite mai multe LINK_DISCOVERED)
          const pairExists = prev.find(e =>
            (e.source === src && e.target === tgt) ||
            (e.source === tgt && e.target === src)
          );
          if (pairExists) return prev;
          const ne = { ...mkEdge(p.edge), animated: true };
          setTimeout(() => setEdges(es => es.map(e => e.id === eid ? { ...e, animated: false } : e)), 2000);
          return [...prev, ne];
        });
        break;
      }

      case 'PROCESSING':
        addMsg(`⟳ ${p.deviceIp} — ${p.phase}`, '#353C4A');
        break;

      case 'COMPLETED':
        addMsg(`✓ Finalizat: ${p.totalDevices} device-uri, ${p.totalLinks} link-uri`, '#3DDC84');
        setRunning(false);
        loadTopology();
        break;

      case 'STOPPED':
        addMsg(`⏹ Oprit: ${p.totalDevices} device-uri`, '#F2A93B');
        setRunning(false);
        loadTopology();
        break;

      case 'ERROR':
        addMsg(`✗ ${p.message}`, '#F2545B');
        addErr(p.message || 'Eroare necunoscuta de la backend');
        setRunning(false);
        break;
    }
  }, [addMsg, loadTopology]);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_BASE_URL + '/ws'),
      reconnectDelay: 5000,
    });
    client.onConnect = () => {
      setConnected(true);
      client.subscribe('/topic/discovery-progress', msg => {
        try { wsHandlerRef.current(JSON.parse(msg.body)); } catch {}
      });
    };
    client.onDisconnect = () => setConnected(false);
    client.activate();
    return () => client.deactivate();
  }, []); // empty — re-subscribe nu e necesar

  // ── Scan ─────────────────────────────────────────────────────────────────
  const handleScan = async (e) => {
    e.preventDefault();
    setScanning(true); setScanErr(null); setScanRes(null);
    try {
      // Acceptam mai multe subnet-uri: un CIDR pe linie sau separate prin virgula
      const subnetList = form.subnets
        .split(/[\n,]+/)
        .map(s => s.trim())
        .filter(Boolean);
      if (subnetList.length === 0) {
        setScanErr('Introdu cel putin un subnet (CIDR)');
        setScanning(false);
        return;
      }
      const res = await scanSubnet({
        subnets: subnetList,
        vendor: 'UNKNOWN',
        sshUsername: form.sshUsername,
        sshPassword: form.sshPassword,
        snmpCommunity: form.snmpCommunity || undefined,
        autoStartDiscovery: true,
      });
      if (res) {
        setScanRes(res);
        if (res.discoveryStarted) setRunning(true);
      }
    } catch (err) {
      setScanErr(err.response?.data?.error || err.message || 'Eroare');
    } finally {
      setScanning(false);
    }
  };

  // ── Reset ─────────────────────────────────────────────────────────────────
  const handleReset = async () => {
    if (!confirmReset) { setConfirmReset(true); return; }
    setConfirmReset(false);
    try {
      await resetTopology();
      setNodes([]); setEdges([]); setSelectedId(null);
      setMessages([]); setErrors([]);
      addMsg('⊘ Topologie resetata', '#F2A93B');
    } catch (err) {
      addMsg(`✗ Reset esuat: ${err.message}`, '#F2545B');
      addErr(`Reset esuat: ${err.message}`);
    }
  };

  const handleStop = async () => {
    try { await stopDiscovery(); } catch {}
  };

  // ── Styles helpers ────────────────────────────────────────────────────────
  const input = {
    background: '#0B0E14', border: '1px solid #252A35',
    borderRadius: 6, color: '#E4E7EB',
    fontFamily: 'JetBrains Mono,monospace', fontSize: 12,
    padding: '7px 10px', width: '100%', boxSizing: 'border-box', outline: 'none',
  };
  const lbl = {
    fontSize: 10, color: '#5A6275', textTransform: 'uppercase',
    letterSpacing: 0.5, marginBottom: 4, display: 'block',
  };

  const isEmpty = !loading && nodes.length === 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#0B0E14' }}>

      {/* ══ HEADER ══════════════════════════════════════════════════════════ */}
      <header style={{
        height: 50, flexShrink: 0, display: 'flex', alignItems: 'center',
        padding: '0 20px', gap: 10,
        background: '#131720', borderBottom: '1px solid #252A35',
      }}>
        <div style={{ width: 7, height: 7, borderRadius: 2, background: '#3DDC84' }} />
        <span style={{ fontFamily: 'JetBrains Mono,monospace', fontSize: 13, fontWeight: 700, color: '#E4E7EB' }}>
          topology-discovery
        </span>
        <span style={{ fontSize: 11, color: '#5A6275', fontFamily: 'JetBrains Mono,monospace' }}>
          L2/L3 · Juniper · Arista · MikroTik
        </span>
      </header>

      {/* ══ BODY ════════════════════════════════════════════════════════════ */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>

        {/* ── SIDEBAR ──────────────────────────────────────────────────── */}
        <aside style={{
          width: 300, flexShrink: 0,
          background: '#0F1218', borderRight: '1px solid #252A35',
          display: 'flex', flexDirection: 'column',
          overflowY: 'auto', overflowX: 'hidden',
        }}>

          {/* Butoane principale */}
          <div style={{
            padding: '12px 14px', borderBottom: '1px solid #252A35',
            display: 'flex', gap: 6, flexWrap: 'wrap',
          }}>
            <Btn
              color={formOpen ? '#252A35' : '#3DDC84'}
              text={formOpen ? '#8B93A3' : '#0B0E14'}
              onClick={() => {
                setFormOpen(o => !o);
                setConfirmReset(false);
                setScanRes(null);
                setScanErr(null);
                // reset form cand se inchide (nu lasam parola vizibila la redeschidere)
                if (formOpen) setForm(f => ({ ...f, sshPassword: '', snmpCommunity: '' }));
              }}
            >
              {formOpen ? '✕ Inchide' : '+ Scan subnet'}
            </Btn>

            <Btn onClick={loadTopology} title="Reincarca">↻</Btn>
            <Btn onClick={applyLayout} title="Auto-aranjare layout">⊞ Layout</Btn>

            {running && (
              <Btn color="rgba(242,84,91,0.12)" text="#F2545B" border="1px solid #F2545B"
                onClick={handleStop}>
                ⏹ Stop
              </Btn>
            )}

            <Btn
              color={confirmReset ? '#F2545B' : 'transparent'}
              text={confirmReset ? '#fff' : '#8B93A3'}
              onClick={handleReset}
              onBlur={() => setTimeout(() => setConfirmReset(false), 150)}
            >
              {confirmReset ? '⚠ Confirma reset' : '⊘ Reset'}
            </Btn>
          </div>

          {/* Formular scan */}
          {formOpen && (
            <form onSubmit={handleScan} style={{ padding: '14px 14px 0', borderBottom: '1px solid #252A35' }}>
              <div style={{ marginBottom: 10 }}>
                <label style={lbl}>Subnet-uri (CIDR) — unul pe linie</label>
                <textarea
                  style={{ ...input, resize: 'vertical', minHeight: 58, lineHeight: 1.5 }}
                  value={form.subnets}
                  rows={3}
                  placeholder={'192.168.1.0/24\n10.0.0.0/24'}
                  onChange={e => setForm(f => ({ ...f, subnets: e.target.value }))} required />
                <div style={{ fontSize: 9, color: '#5A6275', marginTop: 3 }}>
                  Mai multe subnet-uri: cate unul pe linie (sau separate prin virgula)
                </div>
              </div>

<div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
                <div style={{ flex: 1 }}>
                  <label style={lbl}>SSH user</label>
                  <input style={input} value={form.sshUsername}
                    onChange={e => setForm(f => ({ ...f, sshUsername: e.target.value }))} required />
                </div>
                <div style={{ flex: 1 }}>
                  <label style={lbl}>SSH parola</label>
                  <input type="password" style={input} value={form.sshPassword}
                    onChange={e => setForm(f => ({ ...f, sshPassword: e.target.value }))} required />
                </div>
              </div>

              <div style={{ marginBottom: 12 }}>
                <label style={lbl}>SNMP community</label>
                <input style={input} value={form.snmpCommunity} placeholder="public"
                  onChange={e => setForm(f => ({ ...f, snmpCommunity: e.target.value }))} />
              </div>

              <button type="submit" disabled={scanning} style={{
                width: '100%', border: 'none', borderRadius: 6, padding: '8px 0',
                fontSize: 12, fontWeight: 700, cursor: scanning ? 'default' : 'pointer',
                background: scanning ? '#1A1F2B' : '#4D9DF2',
                color: scanning ? '#5A6275' : '#fff',
                marginBottom: 10,
              }}>
                {scanning ? '⟳ Se scaneaza...' : 'Porneste discovery'}
              </button>

              {scanErr && (
                <div style={{ fontSize: 11, color: '#F2545B', marginBottom: 10 }}>{scanErr}</div>
              )}
              {scanRes && (
                <div style={{ fontSize: 11, color: '#3DDC84', marginBottom: 10 }}>
                  {scanRes.liveHostsFound} device-uri gasite
                  {Array.isArray(scanRes.subnetsScanned) ? ` in ${scanRes.subnetsScanned.length} subnet-uri` : ''}.
                  {scanRes.discoveryStarted ? ' Discovery pornit.' : ''}
                </div>
              )}
            </form>
          )}

          {/* Status live */}
          <div style={{ flex: 1, padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
            {/* Indicator conectare */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
              <div style={{
                width: 6, height: 6, borderRadius: '50%',
                background: connected ? '#3DDC84' : '#353C4A',
              }} />
              <span style={{ fontSize: 10, color: '#5A6275', textTransform: 'uppercase', letterSpacing: 0.4 }}>
                {connected ? 'Conectat' : 'Deconectat'}
              </span>
              {running && (
                <span style={{
                  marginLeft: 'auto', fontSize: 10, color: '#4D9DF2',
                  display: 'flex', alignItems: 'center', gap: 4,
                }}>
                  <span style={{
                    width: 5, height: 5, borderRadius: '50%', background: '#4D9DF2',
                    animation: 'pulse 1.5s ease-in-out infinite',
                    display: 'inline-block',
                  }} />
                  Activ
                </span>
              )}
            </div>

            {/* Mesaje */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              {messages.map(m => (
                <div key={m.id} className="msg-row" style={{
                  fontFamily: 'JetBrains Mono,monospace', fontSize: 11,
                  color: m.color, padding: '4px 0',
                  borderBottom: '1px solid #1A1F2B',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>
                  {m.text}
                </div>
              ))}
            </div>
          </div>

          {/* Erori backend */}
          {errors.length > 0 && (
            <div style={{ borderTop: '1px solid rgba(242,84,91,0.2)', padding: '10px 14px' }}>
              <div style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                marginBottom: 6,
              }}>
                <span style={{ fontSize: 10, color: '#F2545B', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                  ✗ Erori ({errors.length})
                </span>
                <button onClick={() => setErrors([])} style={{
                  background: 'transparent', border: 'none', color: '#5A6275',
                  fontSize: 12, cursor: 'pointer', padding: '0 2px',
                }}>✕</button>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {errors.map(e => (
                  <div key={e.id} className="err-card" style={{
                    background: 'rgba(242,84,91,0.07)',
                    border: '1px solid rgba(242,84,91,0.2)',
                    borderRadius: 5, padding: '6px 8px',
                  }}>
                    <div style={{ fontFamily: 'JetBrains Mono,monospace', fontSize: 10, color: '#F2545B', wordBreak: 'break-word' }}>
                      {e.text}
                    </div>
                    <div style={{ fontSize: 9, color: '#5A6275', marginTop: 2 }}>{e.ts}</div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Stats */}
          <div style={{
            padding: '10px 14px', borderTop: '1px solid #252A35',
            display: 'flex', gap: 20,
          }}>
            <StatPill label="Noduri" value={nodes.length} />
            <StatPill label="Link-uri" value={edges.length} />
          </div>
        </aside>

        {/* ── CANVAS ───────────────────────────────────────────────────── */}
        <div style={{ flex: 1, position: 'relative', minWidth: 0 }}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onNodeClick={(_, n) => setSelectedId(n.id)}
            onPaneClick={() => { setSelectedId(null); setConfirmReset(false); }}
            onNodeDragStop={onNodeDragStop}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            onInit={inst => { rfRef.current = inst; }}
            fitView
            fitViewOptions={{ padding: 0.12 }}
            proOptions={{ hideAttribution: true }}
            minZoom={0.05}
            maxZoom={2.5}
          >
            <Background color="#1A1F2B" gap={28} size={1} />
            <Controls style={{
              background: '#131720', border: '1px solid #252A35', borderRadius: 8,
            }} />
            <MiniMap
              nodeColor={n => {
                const s = n.data?.status;
                if (s === 'ACTIVE')      return '#3DDC84';
                if (s === 'ERROR')       return '#F2545B';
                if (s === 'UNREACHABLE') return '#F2A93B';
                if (s === 'POLLING')     return '#4D9DF2';
                return '#5A6275';
              }}
              maskColor="rgba(11,14,20,0.8)"
              style={{ background: '#131720', border: '1px solid #252A35', borderRadius: 8 }}
            />
          </ReactFlow>

          {/* Loading bar deasupra canvas-ului */}
          {(loading || running) && (
            <div style={{
              position: 'absolute', top: 0, left: 0, right: 0,
              height: 2, zIndex: 20, overflow: 'hidden',
            }}>
              <div style={{
                height: '100%',
                background: running ? '#3DDC84' : '#4D9DF2',
                animation: 'loadbar 1.6s ease-in-out infinite',
              }} />
            </div>
          )}

          {/* Overlay-uri */}
          {loading && <CanvasMsg>Se incarca topologia...</CanvasMsg>}
          {topoErr && !loading && <CanvasMsg error>Eroare: {topoErr}</CanvasMsg>}
          {isEmpty && !topoErr && (
            <CanvasMsg>Nicio topologie. Foloseste "+ Scan subnet" din bara laterala.</CanvasMsg>
          )}

          {/* Panou detalii device */}
          {selectedId && (
            <DeviceDetailsPanel deviceId={selectedId} onClose={() => setSelectedId(null)} />
          )}
        </div>
      </div>

      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; }
          50%      { opacity: 0.25; }
        }
        @keyframes loadbar {
          0%   { transform: translateX(-100%); width: 40%; }
          50%  { transform: translateX(80%);   width: 60%; }
          100% { transform: translateX(200%);  width: 40%; }
        }
        @keyframes nodeAppear {
          0%   { opacity: 0; transform: scale(0.55) translateY(16px); }
          65%  { opacity: 1; transform: scale(1.03) translateY(-3px); }
          100% { opacity: 1; transform: scale(1)    translateY(0);    }
        }
        @keyframes fadeSlideIn {
          from { opacity: 0; transform: translateY(5px); }
          to   { opacity: 1; transform: translateY(0);   }
        }
        @keyframes dashmove {
          from { stroke-dashoffset: 20; }
          to   { stroke-dashoffset: 0;  }
        }
        /* Tranzitie lina la re-layout */
        .react-flow__node { transition: transform 0.35s cubic-bezier(0.4,0,0.2,1); }
        .react-flow__node.selected { transition: none; }
        .msg-row  { animation: fadeSlideIn 0.22s ease both; }
        .err-card { animation: fadeSlideIn 0.18s ease both; }
      `}</style>
    </div>
  );
}

// ─── Mici componente helper ──────────────────────────────────────────────────
function Btn({ children, color = 'transparent', text = '#8B93A3', border = '1px solid #252A35', onClick, onBlur, title }) {
  return (
    <button
      onClick={onClick}
      onBlur={onBlur}
      title={title}
      style={{
        background: color, color: text,
        border, borderRadius: 6,
        padding: '6px 11px', fontSize: 11, fontWeight: 600,
        cursor: 'pointer', fontFamily: 'Inter,sans-serif',
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </button>
  );
}

function StatPill({ label, value }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1 }}>
      <span style={{
        fontFamily: 'JetBrains Mono,monospace', fontSize: 18,
        fontWeight: 700, color: '#E4E7EB', lineHeight: 1,
      }}>
        {value}
      </span>
      <span style={{ fontSize: 9, color: '#5A6275', textTransform: 'uppercase', letterSpacing: 0.6 }}>
        {label}
      </span>
    </div>
  );
}

function CanvasMsg({ children, error }) {
  return (
    <div style={{
      position: 'absolute', top: '50%', left: '50%',
      transform: 'translate(-50%, -50%)',
      color: error ? '#F2545B' : '#353C4A',
      fontFamily: 'JetBrains Mono,monospace', fontSize: 13,
      textAlign: 'center', pointerEvents: 'none',
    }}>
      {children}
    </div>
  );
}
