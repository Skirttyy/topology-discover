import React, { useCallback } from 'react';
import { useStore, getBezierPath, EdgeLabelRenderer, BaseEdge } from 'reactflow';
import { getEdgeParams } from './floating';

const endLabelSt = {
  position: 'absolute',
  fontFamily: 'JetBrains Mono, monospace',
  fontSize: 9,
  fontWeight: 500,
  color: '#6B7485',
  background: 'rgba(11,14,20,0.85)',
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
  color: '#9BA3B4',
  background: 'rgba(11,14,20,0.92)',
  padding: '2px 7px',
  borderRadius: 4,
  pointerEvents: 'none',
  whiteSpace: 'nowrap',
  border: '1px solid #2A3140',
  letterSpacing: 0.3,
};

/**
 * Floating edge: citeste pozitiile reale ale nodurilor din store si calculeaza
 * dinamic punctele de conexiune pe perimetrul fiecarui nod. Se actualizeaza
 * automat cand nodurile se misca. Functioneaza pe orice topologie/layout.
 */
export default function LabeledEdge({ id, source, target, style, data, animated, markerEnd }) {
  const sourceNode = useStore(useCallback((s) => s.nodeInternals.get(source), [source]));
  const targetNode = useStore(useCallback((s) => s.nodeInternals.get(target), [target]));

  if (!sourceNode || !targetNode) return null;

  const { sx, sy, tx, ty, sourcePos, targetPos } = getEdgeParams(sourceNode, targetNode);

  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX: sx, sourceY: sy, sourcePosition: sourcePos,
    targetX: tx, targetY: ty, targetPosition: targetPos,
  });

  // Pozitiile label-urilor de la capete, de-a lungul liniei drepte sursa→tinta
  const dx = tx - sx, dy = ty - sy;
  const srcLX = sx + dx * 0.24, srcLY = sy + dy * 0.24;
  const tgtLX = sx + dx * 0.76, tgtLY = sy + dy * 0.76;

  const si = data?.sourceLabel;
  const ti = data?.targetLabel;
  let centerLabel = null;
  if (si && ti) centerLabel = `${si} ↔ ${ti}`;
  else          centerLabel = si || ti || null;

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        style={{
          ...style,
          strokeDasharray: animated ? '6 3' : undefined,
          animation: animated ? 'dashmove 0.5s linear infinite' : undefined,
        }}
      />
      <EdgeLabelRenderer>
        {centerLabel && (
          <div className="nodrag nopan" style={{ ...centerLabelSt, transform: `translate(-50%,-50%) translate(${labelX}px,${labelY}px)` }}>
            {centerLabel}
          </div>
        )}
        {si && (
          <div className="nodrag nopan" style={{ ...endLabelSt, transform: `translate(-50%,-50%) translate(${srcLX}px,${srcLY}px)` }}>
            {si}
          </div>
        )}
        {ti && (
          <div className="nodrag nopan" style={{ ...endLabelSt, transform: `translate(-50%,-50%) translate(${tgtLX}px,${tgtLY}px)` }}>
            {ti}
          </div>
        )}
      </EdgeLabelRenderer>
    </>
  );
}
