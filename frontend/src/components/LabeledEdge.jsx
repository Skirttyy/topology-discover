import React from 'react';
import { getBezierPath, EdgeLabelRenderer, BaseEdge } from 'reactflow';

const labelSt = {
  position: 'absolute',
  fontFamily: 'JetBrains Mono, monospace',
  fontSize: 10,
  fontWeight: 600,
  color: '#8B93A3',
  background: 'rgba(11,14,20,0.88)',
  padding: '1px 5px',
  borderRadius: 3,
  pointerEvents: 'none',
  whiteSpace: 'nowrap',
  border: '1px solid #252A35',
};

export default function LabeledEdge({
  id, sourceX, sourceY, targetX, targetY,
  sourcePosition, targetPosition,
  style, data, animated,
}) {
  const [edgePath] = getBezierPath({
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
  });

  // Pozitii pentru label-urile de la fiecare capat
  // t=0.18 = aproape de sursa, t=0.82 = aproape de tinta
  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const srcLX = sourceX + dx * 0.18;
  const srcLY = sourceY + dy * 0.18;
  const tgtLX = sourceX + dx * 0.82;
  const tgtLY = sourceY + dy * 0.82;

  const hasLabels = data?.sourceLabel || data?.targetLabel;

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
      {hasLabels && (
        <EdgeLabelRenderer>
          {data?.sourceLabel && (
            <div
              className="nodrag nopan"
              style={{
                ...labelSt,
                transform: `translate(-50%,-50%) translate(${srcLX}px,${srcLY}px)`,
              }}
            >
              {data.sourceLabel}
            </div>
          )}
          {data?.targetLabel && (
            <div
              className="nodrag nopan"
              style={{
                ...labelSt,
                transform: `translate(-50%,-50%) translate(${tgtLX}px,${tgtLY}px)`,
              }}
            >
              {data.targetLabel}
            </div>
          )}
        </EdgeLabelRenderer>
      )}
    </>
  );
}
