import React, { useEffect, useState, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { WS_BASE_URL } from '../api/client';

export default function DiscoveryStatusBar({ onDiscoveryEvent }) {
  const [lastEvent, setLastEvent] = useState(null);
  const clientRef = useRef(null);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${WS_BASE_URL}/ws`),
      reconnectDelay: 4000,
      onConnect: () => {
        client.subscribe('/topic/discovery-progress', (message) => {
          try {
            const payload = JSON.parse(message.body);
            setLastEvent(payload);
            onDiscoveryEvent?.(payload);
          } catch {
            // ignoram mesaje malformate
          }
        });
      },
    });
    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, [onDiscoveryEvent]);

  if (!lastEvent) return null;

  const isError = lastEvent.type === 'ERROR';
  const isDone = lastEvent.type === 'COMPLETED';

  return (
    <div style={{
      position: 'absolute',
      bottom: 16,
      left: 16,
      zIndex: 10,
      background: 'var(--bg-panel)',
      border: `1px solid ${isError ? 'var(--accent-error)' : 'var(--border-subtle)'}`,
      borderRadius: 8,
      padding: '8px 14px',
      fontFamily: 'var(--font-mono)',
      fontSize: 12,
      color: isError ? 'var(--accent-error)' : isDone ? 'var(--accent-active)' : 'var(--text-secondary)',
      boxShadow: '0 4px 16px rgba(0,0,0,0.35)',
    }}>
      {lastEvent.type === 'PROCESSING' && `Procesez: ${lastEvent.deviceIp}...`}
      {isDone && 'Discovery finalizat.'}
      {isError && `Eroare: ${lastEvent.message}`}
    </div>
  );
}
