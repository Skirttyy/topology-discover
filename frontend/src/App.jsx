import React from 'react';
import TopologyGraph from './components/TopologyGraph';

export default function App() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <header style={{
        height: 52,
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        padding: '0 20px',
        borderBottom: '1px solid var(--border-subtle)',
        background: 'var(--bg-panel)',
      }}>
        <div style={{
          width: 8, height: 8, borderRadius: 2,
          background: 'var(--accent-active)', marginRight: 10,
        }} />
        <span style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 14,
          fontWeight: 600,
          letterSpacing: 0.3,
        }}>
          topology-discovery
        </span>
        <span style={{
          fontSize: 11,
          color: 'var(--text-tertiary)',
          marginLeft: 10,
          fontFamily: 'var(--font-mono)',
        }}>
          L2/L3 · Juniper · Arista
        </span>
      </header>

      <main style={{ flex: 1, minHeight: 0 }}>
        <TopologyGraph />
      </main>
    </div>
  );
}
