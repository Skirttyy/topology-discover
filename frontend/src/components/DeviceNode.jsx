import React, { memo } from 'react';
import { Handle, Position } from 'reactflow';

const STATUS_CONFIG = {
  ACTIVE:      { color: '#3DDC84', pulse: true },
  DISCOVERED:  { color: '#5A6275', pulse: false },
  POLLING:     { color: '#4D9DF2', pulse: true },
  UNREACHABLE: { color: '#F2A93B', pulse: false },
  ERROR:       { color: '#F2545B', pulse: false },
};

const VENDOR_COLOR = {
  JUNIPER: '#7C8CF8',
  ARISTA:  '#F28C4D',
  UNKNOWN: '#5A6275',
};

const VENDOR_ICON = {
  JUNIPER: 'J',
  ARISTA:  'A',
  UNKNOWN: '?',
};

function DeviceNode({ data, selected }) {
  const status  = STATUS_CONFIG[data.status] || STATUS_CONFIG.DISCOVERED;
  const vcolor  = VENDOR_COLOR[data.vendor] || VENDOR_COLOR.UNKNOWN;

  return (
    <div style={{
      background: selected ? '#1E2535' : '#131720',
      border: `1.5px solid ${selected ? '#4D9DF2' : '#252A35'}`,
      borderRadius: 10,
      minWidth: 200,
      boxShadow: selected
        ? '0 0 0 3px rgba(77,157,242,0.25), 0 4px 20px rgba(0,0,0,0.5)'
        : '0 2px 12px rgba(0,0,0,0.4)',
      fontFamily: 'var(--font-ui)',
      overflow: 'hidden',
      cursor: 'pointer',
      transition: 'all 0.2s ease',
    }}>
      <Handle type="target" position={Position.Top}
        style={{ background: '#353C4A', width: 8, height: 8, border: '2px solid #252A35' }} />
      <Handle type="source" position={Position.Bottom}
        style={{ background: '#353C4A', width: 8, height: 8, border: '2px solid #252A35' }} />

      {/* vendor color strip */}
      <div style={{ height: 3, background: vcolor }} />

      <div style={{ padding: '10px 14px 12px' }}>
        {/* header row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          {/* vendor badge */}
          <div style={{
            width: 22, height: 22, borderRadius: 5,
            background: vcolor + '22',
            border: `1px solid ${vcolor}55`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 11, fontWeight: 700, color: vcolor,
            flexShrink: 0,
          }}>
            {VENDOR_ICON[data.vendor] || '?'}
          </div>

          {/* status dot */}
          <div style={{
            width: 7, height: 7, borderRadius: '50%',
            background: status.color, flexShrink: 0,
            animation: status.pulse ? 'topo-pulse 1.8s ease-in-out infinite' : 'none',
          }} />

          <span style={{
            fontSize: 10, color: 'var(--text-tertiary)',
            textTransform: 'uppercase', letterSpacing: 0.5,
          }}>
            {data.vendor || 'UNKNOWN'}
          </span>
        </div>

        {/* hostname */}
        <div style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 13, fontWeight: 700,
          color: 'var(--text-primary)',
          marginBottom: 3,
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>
          {data.label}
        </div>

        {/* IP */}
        <div style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 11, color: 'var(--text-secondary)',
          marginBottom: data.model ? 4 : 0,
        }}>
          {data.managementIp}
        </div>

        {/* model */}
        {data.model && (
          <div style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>
            {data.model} {data.osVersion ? `· ${data.osVersion}` : ''}
          </div>
        )}

        {/* UNKNOWN badge cu sysDescr hint */}
        {data.vendor === 'UNKNOWN' && data.sysDescr && (
          <div style={{
            marginTop: 6, fontSize: 10,
            color: '#F2A93B', background: 'rgba(242,169,59,0.08)',
            border: '1px solid rgba(242,169,59,0.2)',
            borderRadius: 4, padding: '2px 6px',
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          }} title={data.sysDescr}>
            {data.sysDescr.substring(0, 40)}...
          </div>
        )}
      </div>

      <style>{`
        @keyframes topo-pulse {
          0%   { box-shadow: 0 0 0 0 ${status.color}66; }
          70%  { box-shadow: 0 0 0 7px transparent; }
          100% { box-shadow: 0 0 0 0 transparent; }
        }
      `}</style>
    </div>
  );
}

export default memo(DeviceNode);
