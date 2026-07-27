import type { SchemaDoc } from "../types";
import { rowBool, rowNum, rowStr } from "../types";

// 구 구현(app.js computeLayout)과 동일한 도메인 그리드 자동 배치 규칙.
export const NODE_W = 150;
export const NODE_H = 34;
const GAP_Y = 46;
const COL_W = 330;
const PAD = 40;
const GRID_COLS = 5;
const TWO_COL_THRESHOLD = 12;

export interface NodeView {
  name: string;
  domain: string;
  desc: string;
  hub: boolean;
  x: number;
  y: number;
}

export interface DomainLabel {
  key: string;
  name: string;
  color: string;
  x: number;
  y: number;
}

export interface LayoutResult {
  nodes: Record<string, NodeView>;
  labels: DomainLabel[];
  countByDomain: Record<string, number>;
}

const innerColsOf = (count: number): number => (count > TWO_COL_THRESHOLD ? 2 : 1);
const rowsOf = (count: number): number => Math.ceil(count / innerColsOf(count));

/** pos_x/pos_y가 NULL인 노드는 자동 배치하고, 값이 있으면 저장 좌표로 덮어쓴다. */
export function computeLayout(doc: SchemaDoc): LayoutResult {
  const domainOrder = Object.keys(doc.domains);
  const byDomain = new Map<string, string[]>(domainOrder.map((d) => [d, []]));
  const nodes: Record<string, NodeView> = {};

  for (const row of doc.tables) {
    const name = rowStr(row, 0);
    const rawDomain = rowStr(row, 1);
    const domain = byDomain.has(rawDomain) ? rawDomain : domainOrder[0];
    nodes[name] = { name, domain, desc: rowStr(row, 2), hub: rowBool(row, 3), x: 0, y: 0 };
    byDomain.get(domain)?.push(name);
  }

  const numGridRows = Math.ceil(domainOrder.length / GRID_COLS);
  const gridRowY: number[] = [];
  let acc = 0;
  for (let gr = 0; gr < numGridRows; gr++) {
    let maxRows = 0;
    domainOrder.forEach((d, i) => {
      if (Math.floor(i / GRID_COLS) === gr) {
        maxRows = Math.max(maxRows, rowsOf(byDomain.get(d)?.length ?? 0));
      }
    });
    gridRowY[gr] = acc;
    acc += maxRows * GAP_Y + 90;
  }

  const labels: DomainLabel[] = [];
  const countByDomain: Record<string, number> = {};
  domainOrder.forEach((d, di) => {
    const list = byDomain.get(d) ?? [];
    countByDomain[d] = list.length;
    const colBaseX = (di % GRID_COLS) * COL_W + PAD;
    const baseY = gridRowY[Math.floor(di / GRID_COLS)];
    const innerCols = innerColsOf(list.length);
    list.forEach((name, i) => {
      nodes[name].x = colBaseX + (i % innerCols) * (NODE_W + 12);
      nodes[name].y = baseY + 60 + Math.floor(i / innerCols) * GAP_Y;
    });
    labels.push({ key: d, name: doc.domains[d].name, color: doc.domains[d].color, x: colBaseX, y: baseY + 30 });
  });

  for (const row of doc.tables) {
    const x = rowNum(row, 4);
    const y = rowNum(row, 5);
    if (x !== null && y !== null) {
      const node = nodes[rowStr(row, 0)];
      node.x = x;
      node.y = y;
    }
  }
  return { nodes, labels, countByDomain };
}
