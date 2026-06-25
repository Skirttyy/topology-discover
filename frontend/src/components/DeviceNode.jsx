import React, { memo } from 'react';
import { Handle, Position } from 'reactflow';

// ── Status ─────────────────────────────────────────────────────────────────
const STATUS = {
  ACTIVE:      { color: '#3DDC84', pulse: true  },
  POLLING:     { color: '#4D9DF2', pulse: true  },
  DISCOVERED:  { color: '#5A6275', pulse: false },
  UNREACHABLE: { color: '#F2A93B', pulse: false },
  ERROR:       { color: '#F2545B', pulse: false },
};

// ── Vendor ─────────────────────────────────────────────────────────────────
const VENDOR_COLOR = {
  JUNIPER:  '#7C8CF8',
  ARISTA:   '#F28C4D',
  MIKROTIK: '#FF6B35',
  UNKNOWN:  '#5A6275',
};

// ── Device type detection ───────────────────────────────────────────────────
function detectType(data) {
  const model   = (data.model   || '').toLowerCase();
  const label   = (data.label   || '').toLowerCase();
  const ip      = (data.managementIp || '').toLowerCase();
  const vendor  = data.vendor   || 'UNKNOWN';

  if (ip.startsWith('lldp:')) return 'PLACEHOLDER';

  if (label.includes('wan') || label.includes('tunnel') || label.includes('internet')
      || label.includes('fw') || label.includes('firewall') || label.includes('gw'))
    return 'WAN';

  if (model.includes('vmx') || model.includes('-mx') || model.includes('ptx')
      || label.includes('core') || label.includes('border') || label.includes('pe-')
      || (vendor === 'MIKROTIK'))
    return 'ROUTER';

  if (model.includes('qfx') || model.includes('vqfx') || model.includes('ex')
      || model.includes('arista') || model.includes('7050') || model.includes('7280')
      || label.includes('spine') || label.includes('leaf') || label.includes('sw'))
    return 'SWITCH';

  return 'UNKNOWN';
}

// ── SVG Icons ──────────────────────────────────────────────────────────────
const IconRouter = ({ color }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
    <circle cx="12" cy="12" r="10" stroke={color} strokeWidth="1.5"/>
    <path d="M12 6v12M6 12h12" stroke={color} strokeWidth="1.5" strokeLinecap="round"/>
    <path d="M9 9l6 6M15 9l-6 6" stroke={color} strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

const IconSwitch = ({ color }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
    <rect x="2" y="5" width="20" height="4" rx="2" stroke={color} strokeWidth="1.5"/>
    <rect x="2" y="11" width="20" height="4" rx="2" stroke={color} strokeWidth="1.5"/>
    <rect x="2" y="17" width="20" height="4" rx="2" stroke={color} strokeWidth="1.5"/>
    <circle cx="18" cy="7"  r="1" fill={color}/>
    <circle cx="18" cy="13" r="1" fill={color}/>
    <circle cx="18" cy="19" r="1" fill={color}/>
  </svg>
);

const IconWan = ({ color }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
    <path d="M3 12a9 9 0 1 0 18 0 9 9 0 0 0-18 0Z" stroke={color} strokeWidth="1.5"/>
    <path d="M3 12h18M12 3c-2.5 3-4 5.5-4 9s1.5 6 4 9M12 3c2.5 3 4 5.5 4 9s-1.5 6-4 9"
          stroke={color} strokeWidth="1.5"/>
  </svg>
);

const IconUnknown = ({ color }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
    <circle cx="12" cy="12" r="10" stroke={color} strokeWidth="1.5" strokeDasharray="4 2"/>
    <text x="12" y="16" textAnchor="middle" fill={color} fontSize="12" fontWeight="700">?</text>
  </svg>
);

// ── Handle style ───────────────────────────────────────────────────────────
const H = { background: '#353C4A', width: 7, height: 7, border: '2px solid #1A1F2B' };

const ERROR_COLOR   = '#F2545B';
const WARNING_COLOR = '#F2A93B';

// ── Main component ─────────────────────────────────────────────────────────
function DeviceNode({ data, selected }) {
  const type      = detectType(data);
  const status    = STATUS[data.status] || STATUS.DISCOVERED;
  const vcolor    = VENDOR_COLOR[data.vendor] || VENDOR_COLOR.UNKNOWN;
  const hasError  = data.status === 'ERROR';
  const hasWarn   = !hasError && data.lastError && data.lastError.length > 0;

  // ── PLACEHOLDER (lldp:*) — dim, dashed ──────────────────────────────────
  if (type === 'PLACEHOLDER') {
    return (
      <div style={{
        background: 'rgba(11,14,20,0.7)',
        border: `1.5px dashed ${selected ? '#4D9DF2' : '#353C4A'}`,
        borderRadius: 12, minWidth: 170,
        opacity: 0.7, cursor: 'pointer',
        animation: 'nodeAppear 0.3s ease both',
        fontFamily: 'var(--font-ui)',
      }}>
        <Handle type="target" position={Position.Top}    style={H} />
        <Handle type="source" position={Position.Bottom} style={H} />
        <div style={{ padding: '8px 12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
            <IconUnknown color="#5A6275"/>
            <span style={{ fontSize: 9, color: '#5A6275', textTransform: 'uppercase', letterSpacing: 0.5 }}>
              NEDESCOPERIT
            </span>
          </div>
          <div style={{
            fontFamily: 'var(--font-mono)', fontSize: 12, fontWeight: 600,
            color: '#8B93A3', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          }}>
            {data.label}
          </div>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: '#5A6275' }}>
            {data.managementIp}
          </div>
        </div>
      </div>
    );
  }

  // ── WAN / GATEWAY ─────────────────────────────────────────────────────────
  if (type === 'WAN') {
    return (
      <div style={{
        background: selected ? '#1A1520' : '#0F1018',
        border: `1.5px solid ${selected ? '#4D9DF2' : '#F2A93B44'}`,
        borderRadius: 20,
        minWidth: 170,
        boxShadow: selected
          ? '0 0 0 3px rgba(77,157,242,0.2), 0 4px 20px rgba(0,0,0,0.5)'
          : '0 0 16px rgba(242,169,59,0.08), 0 2px 8px rgba(0,0,0,0.3)',
        cursor: 'pointer', fontFamily: 'var(--font-ui)',
        animation: 'nodeAppear 0.35s cubic-bezier(0.34,1.56,0.64,1) both',
      }}>
        <Handle type="target" position={Position.Top}    style={H} />
        <Handle type="source" position={Position.Bottom} style={H} />
        <div style={{ padding: '10px 14px 11px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 6 }}>
            <IconWan color="#F2A93B"/>
            <div style={{
              width: 6, height: 6, borderRadius: '50%', background: status.color, flexShrink: 0,
              animation: status.pulse ? 'topo-pulse 1.8s ease-in-out infinite' : 'none',
            }}/>
            <span style={{ fontSize: 9, color: '#F2A93B', textTransform: 'uppercase', letterSpacing: 0.5 }}>
              {data.vendor !== 'UNKNOWN' ? data.vendor : 'WAN'}
            </span>
          </div>
          <div style={{
            fontFamily: 'var(--font-mono)', fontSize: 13, fontWeight: 700,
            color: '#E4E7EB', marginBottom: 2,
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          }}>
            {data.label}
          </div>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: '#8B93A3' }}>
            {data.managementIp}
          </div>
        </div>
        <PulseStyle color={status.color}/>
      </div>
    );
  }

  // ── ROUTER ────────────────────────────────────────────────────────────────
  if (type === 'ROUTER') {
    return (
      <div style={{
        background: selected ? '#1A1E2D' : hasError ? '#180E0E' : '#12151F',
        border: `1.5px solid ${selected ? '#4D9DF2' : hasError ? ERROR_COLOR + '66' : hasWarn ? WARNING_COLOR + '44' : vcolor + '55'}`,
        borderLeft: `3px solid ${hasError ? ERROR_COLOR : hasWarn ? WARNING_COLOR : vcolor}`,
        borderRadius: 14,
        minWidth: 200,
        boxShadow: selected
          ? `0 0 0 3px rgba(77,157,242,0.2), 0 6px 24px rgba(0,0,0,0.6)`
          : `0 2px 12px rgba(0,0,0,0.4)`,
        cursor: 'pointer', fontFamily: 'var(--font-ui)',
        transition: 'box-shadow 0.2s, border-color 0.2s',
        animation: 'nodeAppear 0.35s cubic-bezier(0.34,1.56,0.64,1) both',
      }}>
        <Handle type="target" position={Position.Top}    style={H} />
        <Handle type="source" position={Position.Bottom} style={H} />
        <div style={{ padding: '10px 14px 12px', paddingLeft: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 7 }}>
            <IconRouter color={vcolor}/>
            <div style={{
              width: 6, height: 6, borderRadius: '50%', background: status.color, flexShrink: 0,
              animation: status.pulse ? 'topo-pulse 1.8s ease-in-out infinite' : 'none',
            }}/>
            <span style={{ fontSize: 9, color: vcolor, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 }}>
              {data.vendor || 'ROUTER'}
            </span>
          </div>
          <div style={{
            fontFamily: 'var(--font-mono)', fontSize: 13, fontWeight: 700,
            color: '#E4E7EB', marginBottom: 2,
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          }}>
            {data.label}
          </div>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: '#8B93A3', marginBottom: data.model ? 3 : 0 }}>
            {data.managementIp}
          </div>
          {data.model && (
            <div style={{ fontSize: 10, color: '#5A6275' }}>
              {data.model}{data.osVersion ? ` · ${data.osVersion}` : ''}
            </div>
          )}
        </div>
        <ErrorBadge hasError={hasError} hasWarn={hasWarn}/>
        <PulseStyle color={status.color}/>
      </div>
    );
  }

  // ── SWITCH ────────────────────────────────────────────────────────────────
  // (default pentru SWITCH + UNKNOWN con IP real)
  return (
    <div style={{
      background: selected ? '#141C18' : hasError ? '#180E0E' : '#0E1610',
      border: `1.5px solid ${selected ? '#4D9DF2' : hasError ? ERROR_COLOR + '66' : hasWarn ? WARNING_COLOR + '44' : vcolor + '44'}`,
      borderTop: `3px solid ${hasError ? ERROR_COLOR : hasWarn ? WARNING_COLOR : vcolor}`,
      borderRadius: 8,
      minWidth: 200,
      boxShadow: selected
        ? `0 0 0 3px rgba(77,157,242,0.2), 0 6px 24px rgba(0,0,0,0.6)`
        : `0 2px 12px rgba(0,0,0,0.4)`,
      cursor: 'pointer', fontFamily: 'var(--font-ui)',
      transition: 'box-shadow 0.2s, border-color 0.2s',
      animation: 'nodeAppear 0.4s cubic-bezier(0.34,1.56,0.64,1) both',
    }}>
      <Handle type="target" position={Position.Top}    style={H} />
      <Handle type="source" position={Position.Bottom} style={H} />
      <Handle type="target" position={Position.Left}   style={H} />
      <Handle type="source" position={Position.Right}  style={H} />
      <div style={{ padding: '10px 14px 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 7 }}>
          <IconSwitch color={vcolor}/>
          <div style={{
            width: 6, height: 6, borderRadius: '50%', background: status.color, flexShrink: 0,
            animation: status.pulse ? 'topo-pulse 1.8s ease-in-out infinite' : 'none',
          }}/>
          <span style={{ fontSize: 9, color: vcolor, textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 }}>
            {data.vendor || 'SWITCH'}
          </span>
        </div>
        <div style={{
          fontFamily: 'var(--font-mono)', fontSize: 13, fontWeight: 700,
          color: '#E4E7EB', marginBottom: 2,
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>
          {data.label}
        </div>
        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: '#8B93A3', marginBottom: data.model ? 3 : 0 }}>
          {data.managementIp}
        </div>
        {data.model && (
          <div style={{ fontSize: 10, color: '#5A6275' }}>
            {data.model}{data.osVersion ? ` · ${data.osVersion}` : ''}
          </div>
        )}
      </div>
      <ErrorBadge hasError={hasError} hasWarn={hasWarn}/>
      <PulseStyle color={status.color}/>
    </div>
  );
}

function ErrorBadge({ hasError, hasWarn }) {
  if (!hasError && !hasWarn) return null;
  const color = hasError ? ERROR_COLOR : WARNING_COLOR;
  const icon  = hasError ? '✕' : '⚠';
  const label = hasError ? 'EROARE' : 'AVERTISMENT';
  return (
    <div style={{
      margin: '0 10px 8px',
      padding: '3px 8px',
      borderRadius: 4,
      background: `${color}14`,
      border: `1px solid ${color}44`,
      display: 'flex', alignItems: 'center', gap: 5,
    }}>
      <span style={{ fontSize: 9, color, fontWeight: 700 }}>{icon}</span>
      <span style={{ fontSize: 9, color, textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {label} — click pentru detalii
      </span>
    </div>
  );
}

// keyframe-ul topo-pulse e definit global in index.css — nu mai injectam per nod
function PulseStyle() { return null; }

export default memo(DeviceNode);
