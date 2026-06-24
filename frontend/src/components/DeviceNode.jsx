import React, { memo } from 'react';
import { Handle, Position } from 'reactflow';

const STATUS_CONFIG = {
  ACTIVE: { color: 'var(--accent-active)', label: 'activ', pulse: true },
  DISCOVERED: { color: 'var(--accent-neutral)', label: 'descoperit', pulse: false },
  CONFIGURING: { color: 'var(--accent-info)', label: 'configurare', pulse: true },
  POLLING: { color: 'var(--accent-info)', label: 'interogare', pulse: true },
  UNREACHABLE: { color: 'var(--accent-warning)', label: 'inaccesibil', pulse: false },
  ERROR: { color: 'var(--accent-error)', label: 'eroare', pulse: false },
};

const VENDOR_LABEL = {
  JUNIPER: 'Juniper',
  ARISTA: 'Arista',
  UNKNOWN: 'Necunoscut',
};

function DeviceNode({ data, selected }) {
  const status = STATUS_CONFIG[data.status] || STATUS_CONFIG.DISCOVERED;
  const vendorColor = data.vendor === 'JUNIPER'
    ? 'var(--vendor-juniper)'
    : data.vendor === 'ARISTA'
      ? 'var(--vendor-arista)'
      : 'var(--text-tertiary)';

  return (
    <div
      style={{
        background: 'var(--bg-panel-raised)',
        border: `1px solid ${selected ? 'var(--accent-info)' : 'var(--border-strong)'}`,
        borderRadius: 8,
        minWidth: 180,
        boxShadow: selected
          ? '0 0 0 3px rgba(77, 157, 242, 0.2)'
          : '0 2px 8px rgba(0,0,0,0.3)',
        fontFamily: 'var(--font-ui)',
        overflow: 'hidden',
        cursor: 'pointer',
      }}
    >
      <Handle type="target" position={Position.Top} style={{ background: 'var(--border-strong)', width: 6, height: 6 }} />
      <Handle type="source" position={Position.Bottom} style={{ background: 'var(--border-strong)', width: 6, height: 6 }} />

      {/* vendor strip */}
      <div style={{ height: 3, background: vendorColor }} />

      <div style={{ padding: '10px 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
          <span
            style={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: status.color,
              boxShadow: status.pulse ? `0 0 0 0 ${status.color}` : 'none',
              animation: status.pulse ? 'topo-pulse 1.8s infinite' : 'none',
              flexShrink: 0,
            }}
          />
          <span style={{
            fontSize: 11,
            color: 'var(--text-tertiary)',
            textTransform: 'uppercase',
            letterSpacing: 0.4,
          }}>
            {VENDOR_LABEL[data.vendor] || data.vendor}
          </span>
        </div>

        <div style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 13,
          fontWeight: 600,
          color: 'var(--text-primary)',
          marginBottom: 2,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          maxWidth: 180,
        }}>
          {data.label}
        </div>

        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-secondary)' }}>
          {data.managementIp}
        </div>

        {data.model && (
          <div style={{ fontSize: 10, color: 'var(--text-tertiary)', marginTop: 4 }}>
            {data.model}
          </div>
        )}
      </div>

      <style>{`
        @keyframes topo-pulse {
          0% { box-shadow: 0 0 0 0 ${status.color}66; }
          70% { box-shadow: 0 0 0 6px transparent; }
          100% { box-shadow: 0 0 0 0 transparent; }
        }
      `}</style>
    </div>
  );
}

export default memo(DeviceNode);
