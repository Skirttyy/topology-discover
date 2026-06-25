import React from 'react';
import { getBezierPath, EdgeLabelRenderer, BaseEdge } from 'reactflow';

const endLabelSt = {
  position: 'absolute',
  fontFamily: 'JetBrains Mono, monospace',
  fontSize: 9,
  fontWeight: 500,
  color: '#6B7485',
  background: 'rgba(11,14,20,0.82)',
  padding: '1px 4px',
  borderRadius: 3,
  pointerEvents: 'none',
  whiteSpace: 'nowrap',
  border: '1px solid #1E2535',
};

const centerLabelSt = {
  position: 'absolute',
  fontFamily: 'JetBrains Mono, monospace',
  fontSize: 10,
  fontWeight: 700,
  color: '#8B93A3',
  background: 'rgba(11,14,20,0.92)',
  padding: '2px 7px',
  borderRadius: 4,
  pointerEvents: 'none',
  whiteSpace: 'nowrap',
  border: '1px solid #252A35',
  letterSpacing: 0.3,
};

export default function LabeledEdge({
  id, sourceX, sourceY, targetX, targetY,
  sourcePosition, targetPosition,
  style, data, animated,
}) {
  const [edgePath, centerX, centerY] = getBezierPath({
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
  });

  // Pozitii pentru labelurile de la capete (t=0.16 / t=0.84 de-a lungul liniei)
  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const srcLX = sourceX + dx * 0.16;
  const srcLY = sourceY + dy * 0.16;
  const tgtLX = sourceX + dx * 0.84;
  const tgtLY = sourceY + dy * 0.84;

  const si = data?.sourceLabel;
  const ti = data?.targetLabel;

  // Label central: "ae0 ↔ ae1" sau "ae0" sau "ae1" — ce avem
  let centerLabel = null;
  if (si && ti) centerLabel = `${si} ↔ ${ti}`;
  else if (si)  centerLabel = si;
  else if (ti)  centerLabel = ti;

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          ...style,
          strokeDasharray: animated ? '6 3' : undefined,
          animation: animated ? 'dashmove 0.5s linear infinite' : undefined,
        }}
      />
      <EdgeLabelRenderer>
        {/* Label central — interfata locala <-> interfata remote */}
        {centerLabel && (
          <div
            className="nodrag nopan"
            style={{
              ...centerLabelSt,
              transform: `translate(-50%,-50%) translate(${centerX}px,${centerY}px)`,
            }}
          >
            {centerLabel}
          </div>
        )}

        {/* Label sursa — aproape de device-ul sursa */}
        {si && (
          <div
            className="nodrag nopan"
            style={{
              ...endLabelSt,
              transform: `translate(-50%,-50%) translate(${srcLX}px,${srcLY}px)`,
            }}
          >
            {si}
          </div>
        )}

        {/* Label destinatie — aproape de device-ul destinatie */}
        {ti && (
          <div
            className="nodrag nopan"
            style={{
              ...endLabelSt,
              transform: `translate(-50%,-50%) translate(${tgtLX}px,${tgtLY}px)`,
            }}
          >
            {ti}
          </div>
        )}
      </EdgeLabelRenderer>
    </>
  );
}
