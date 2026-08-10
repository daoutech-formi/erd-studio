import { memo } from "react";
import { NODE_H, NODE_W } from "./layout";

interface Props {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** N:1(기본, 빈 문자열 포함) / 1:1 / N:M — child→parent 기준 표기. */
  cardinality: string;
  hl: boolean;
  dim: boolean;
}

const HALF_W = NODE_W / 2;
const HALF_H = NODE_H / 2;
const FOOT_LEN = 12;
const FOOT_SPREAD = 6;
const BAR_OFFSET = 9;
const BAR_HALF = 5;

/** 노드 경계의 접속점과 바깥 방향 단위벡터. 좌우로 떨어져 있으면 옆면, 아니면 위/아랫면을 쓴다. */
function anchor(x: number, y: number, ox: number, oy: number): { ax: number; ay: number; ux: number; uy: number } {
  if (Math.abs(ox - x) > NODE_W) {
    const s = ox > x ? 1 : -1;
    return { ax: x + s * HALF_W, ay: y, ux: s, uy: 0 };
  }
  const t = oy >= y ? 1 : -1;
  return { ax: x, ay: y + t * HALF_H, ux: 0, uy: t };
}

/** 까마귀발(다) 마커 — 접속점 (ax,ay)에서 바깥 방향 (ux,uy)로 벌어진다. */
function crowFoot(ax: number, ay: number, ux: number, uy: number): string {
  const px = ax + ux * FOOT_LEN;
  const py = ay + uy * FOOT_LEN;
  const wx = -uy * FOOT_SPREAD;
  const wy = ux * FOOT_SPREAD;
  return `M${px},${py} L${ax + wx},${ay + wy} M${px},${py} L${ax},${ay} M${px},${py} L${ax - wx},${ay - wy}`;
}

/** 단일(1) 마커 — 선에 수직인 짧은 바. */
function oneBar(ax: number, ay: number, ux: number, uy: number): string {
  const bx = ax + ux * BAR_OFFSET;
  const by = ay + uy * BAR_OFFSET;
  const wx = -uy * BAR_HALF;
  const wy = ux * BAR_HALF;
  return `M${bx + wx},${by + wy} L${bx - wx},${by - wy}`;
}

/** 관계 곡선 1개 (child → parent 베지어) + IE 까마귀발 카디널리티 마커. */
export const ErdEdge = memo(function ErdEdge({ x1, y1, x2, y2, cardinality, hl, dim }: Props) {
  const cls = `edge${hl ? " hl" : ""}${dim ? " dim" : ""}`;
  const c = anchor(x1, y1, x2, y2);
  const p = anchor(x2, y2, x1, y1);
  // 접속점의 바깥 방향으로 뻗는 제어점 — 마커 구간에서 곡선이 접선과 일치하게 한다.
  const reach = Math.max(Math.abs(p.ax - c.ax), Math.abs(p.ay - c.ay)) / 2;
  const c1x = c.ax + c.ux * reach;
  const c1y = c.ay + c.uy * reach;
  const c2x = p.ax + p.ux * reach;
  const c2y = p.ay + p.uy * reach;
  const childMany = cardinality !== "1:1";
  const parentMany = cardinality === "N:M";
  return (
    <g className={cls}>
      <path d={`M${c.ax},${c.ay} C${c1x},${c1y} ${c2x},${c2y} ${p.ax},${p.ay}`} />
      <path d={childMany ? crowFoot(c.ax, c.ay, c.ux, c.uy) : oneBar(c.ax, c.ay, c.ux, c.uy)} />
      <path d={parentMany ? crowFoot(p.ax, p.ay, p.ux, p.uy) : oneBar(p.ax, p.ay, p.ux, p.uy)} />
    </g>
  );
});
