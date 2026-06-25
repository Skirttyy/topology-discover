import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { WS_BASE_URL } from '../api/client';

/**
 * Bara de status live. Se conecteaza prin WebSocket si primeste:
 * - NODE_DISCOVERED / NODE_UPDATED -> onNodeEvent(node)
 * - LINK_DISCOVERED                -> onLinkEvent(edge)
 * - PROCESSING                     -> afiseaza ce se proceseaza
 * - COMPLETED / ERROR              -> stare finala
 */
export default function DiscoveryStatusBar({ onNodeEvent, onLinkEvent, onCompleted, onStarted, onFinished }) {
  const [messages, setMessages] = useState([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef(null);

  const addMsg = useCallback((text, color = 'var(--text-secondary)') => {
    setMessages(prev => [...prev.slice(-4), { text, color, id: Date.now() }]);
  }, []);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_BASE_URL + '/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/topic/discovery-progress', (message) => {
          try {
            const payload = JSON.parse(message.body);
            handleEvent(payload);
          } catch (e) { /* ignoram */ }
        });
      },
      onDisconnect: () => setConnected(false),
    });

    function handleEvent(payload) {
      switch (payload.type) {
        case 'NODE_DISCOVERED':
          addMsg(`+ Descoperit: ${payload.node?.label || payload.node?.managementIp}`, 'var(--accent-active)');
          onNodeEvent?.(payload.node, 'discovered');
          onStarted?.();
          break;
        case 'NODE_UPDATED':
          addMsg(`↻ Actualizat: ${payload.node?.label || payload.node?.managementIp}`, 'var(--accent-info)');
          onNodeEvent?.(payload.node, 'updated');
          break;
        case 'LINK_DISCOVERED':
          addMsg(`⟷ Link: ${payload.edge?.source} ↔ ${payload.edge?.target}`, '#7C8CF8');
          onLinkEvent?.(payload.edge);
          break;
        case 'PROCESSING':
          addMsg(`⟳ ${payload.deviceIp} — ${payload.phase}`, 'var(--text-tertiary)');
          break;
        case 'COMPLETED':
          addMsg(`✓ Finalizat: ${payload.totalDevices} device-uri, ${payload.totalLinks} link-uri`, 'var(--accent-active)');
          onCompleted?.();
          onFinished?.();
          break;
        case 'STOPPED':
          addMsg(`⏹ Oprit: ${payload.totalDevices} device-uri, ${payload.totalLinks} link-uri`, 'var(--accent-warning)');
          onCompleted?.();
          onFinished?.();
          break;
        case 'ERROR':
          addMsg(`✗ Eroare: ${payload.message}`, 'var(--accent-error)');
          break;
      }
    }

    client.activate();
    clientRef.current = client;
    return () => client.deactivate();
  }, [addMsg, onNodeEvent, onLinkEvent, onCompleted]);

  if (messages.length === 0 && !connected) return null;

  return (
    <div style={{
      position: 'absolute', bottom: 16, left: 16, zIndex: 10,
      background: 'var(--bg-panel)',
      border: '1px solid var(--border-subtle)',
      borderRadius: 8, padding: '10px 14px',
      maxWidth: 380, minWidth: 220,
      boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6,
        marginBottom: messages.length > 0 ? 8 : 0,
      }}>
        <div style={{
          width: 6, height: 6, borderRadius: '50%',
          background: connected ? 'var(--accent-active)' : 'var(--accent-neutral)',
          animation: connected ? 'topo-pulse 2s infinite' : 'none',
        }} />
        <span style={{ fontSize: 10, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: 0.4 }}>
          {connected ? 'Conectat' : 'Deconectat'}
        </span>
      </div>
      {messages.map((msg) => (
        <div key={msg.id} style={{
          fontFamily: 'var(--font-mono)', fontSize: 11,
          color: msg.color, marginBottom: 2,
          animation: 'fadeIn 0.3s ease',
        }}>
          {msg.text}
        </div>
      ))}
      <style>{`
        @keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
      `}</style>
    </div>
  );
}
