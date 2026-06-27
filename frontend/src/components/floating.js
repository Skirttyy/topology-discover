import { Position } from 'reactflow';

// Cutia unui nod (centru + dimensiuni). In React Flow v11 nodul are
// .width/.height (masurate dupa render) si .positionAbsolute.
function nodeBox(node) {
  const w = node.width  ?? 220;
  const h = node.height ?? 110;
  const p = node.positionAbsolute ?? node.position ?? { x: 0, y: 0 };
  return { cx: p.x + w / 2, cy: p.y + h / 2, w, h };
}

// Punctul in care linia dintre centrele a doua noduri intersecteaza
// perimetrul nodului `self`. Returneaza si pe ce latura cade (pentru curba bezier).
function borderPoint(self, other) {
  const halfW = self.w / 2;
  const halfH = self.h / 2;
  const dx = other.cx - self.cx;
  const dy = other.cy - self.cy;

  if (dx === 0 && dy === 0) {
    return { x: self.cx, y: self.cy + halfH, pos: Position.Bottom };
  }

  // cat trebuie scalat vectorul (dx,dy) ca sa atinga fiecare latura
  const scaleX = dx !== 0 ? halfW / Math.abs(dx) : Infinity;
  const scaleY = dy !== 0 ? halfH / Math.abs(dy) : Infinity;

  if (scaleX < scaleY) {
    // intersecteaza latura stanga sau dreapta
    return {
      x: self.cx + Math.sign(dx) * halfW,
      y: self.cy + dy * scaleX,
      pos: dx > 0 ? Position.Right : Position.Left,
    };
  }
  // intersecteaza latura sus sau jos
  return {
    x: self.cx + dx * scaleY,
    y: self.cy + Math.sign(dy) * halfH,
    pos: dy > 0 ? Position.Bottom : Position.Top,
  };
}

/**
 * Calculeaza punctele de start/sfarsit ale unui edge "floating":
 * conecteaza de la perimetrul nodului sursa la perimetrul nodului destinatie,
 * pe directia care le uneste — functioneaza pe ORICE pozitii ale nodurilor.
 */
export function getEdgeParams(source, target) {
  const a = nodeBox(source);
  const b = nodeBox(target);
  const s = borderPoint(a, b);
  const t = borderPoint(b, a);
  return {
    sx: s.x, sy: s.y, sourcePos: s.pos,
    tx: t.x, ty: t.y, targetPos: t.pos,
  };
}
