import { useCallback, type MutableRefObject } from "react";
import { erdSocket } from "../api/socket";
import type { Op } from "../types";
import { NODE_H } from "./layout";

const MOVE_THROTTLE_MS = 50;
const TRANSLATE_RE = /translate\(([-\d.]+),([-\d.]+)\)/;

/** 드래그 직후의 click 이벤트를 무시하기 위한 플래그 — 메모를 옮기자마자 편집창이 열리는 것을 막는다. */
let suppressClickUntil = 0;
export const wasJustDragged = (): boolean => performance.now() < suppressClickUntil;

interface DragTarget {
  /** move 중계·op 대상 식별자 (테이블명 또는 "memo:{id}"). */
  moveKey: string;
  /** DOM translate의 y(상단)를 문서 좌표 y로 바꾸는 보정값. */
  centerOffsetY: number;
  makeOp: (x: number, y: number) => Op;
}

/**
 * 드래그 공통 처리 — 드래그 중에는 DOM transform만 갱신(+50ms throttle로 move 중계)하고,
 * mouseup 시에만 move op를 보내 DB에 확정한다.
 */
function beginDrag(el: SVGGElement, e: React.MouseEvent<SVGGElement>, target: DragTarget): void {
  e.stopPropagation();
  e.preventDefault();
  const scale = el.getScreenCTM()?.a ?? 1;
  const match = TRANSLATE_RE.exec(el.getAttribute("transform") ?? "");
  const baseX = match ? Number(match[1]) : 0;
  const baseTopY = match ? Number(match[2]) : 0;
  const startCX = e.clientX;
  const startCY = e.clientY;
  const start = { x: baseX, y: baseTopY + target.centerOffsetY };
  let current = { ...start };
  let moved = false;
  let lastSent = 0;

  const onMove = (ev: MouseEvent) => {
    const x = baseX + (ev.clientX - startCX) / scale;
    const topY = baseTopY + (ev.clientY - startCY) / scale;
    current = { x, y: topY + target.centerOffsetY };
    moved = true;
    el.setAttribute("transform", `translate(${x},${topY})`);
    const now = performance.now();
    if (now - lastSent >= MOVE_THROTTLE_MS) {
      lastSent = now;
      erdSocket.sendMove(target.moveKey, current.x, current.y);
    }
  };
  const onUp = () => {
    window.removeEventListener("mousemove", onMove);
    window.removeEventListener("mouseup", onUp);
    if (!moved) {
      return;
    }
    suppressClickUntil = performance.now() + 200;
    erdSocket.sendEditOp(target.makeOp(current.x, current.y), [target.makeOp(start.x, start.y)]);
  };
  window.addEventListener("mousemove", onMove);
  window.addEventListener("mouseup", onUp);
}

/** 편집 모드에서 테이블 노드를 드래그해 옮긴다. 좌표는 노드 세로 중앙 기준. */
export function useNodeDrag(
  nodeEls: MutableRefObject<Map<string, SVGGElement>>,
  editMode: boolean,
): (name: string, e: React.MouseEvent<SVGGElement>) => void {
  return useCallback(
    (name, e) => {
      if (!editMode || e.button !== 0) {
        return;
      }
      const el = nodeEls.current.get(name);
      if (!el) {
        return;
      }
      const user = erdSocket.currentUser();
      beginDrag(el, e, {
        moveKey: name,
        centerOffsetY: NODE_H / 2,
        makeOp: (x, y) => ({ type: "table.move", user, payload: { name, x, y } }),
      });
    },
    [editMode, nodeEls],
  );
}

/** 편집 모드에서 메모를 드래그해 옮긴다. 좌표는 좌상단 기준이라 중앙 보정이 없다. */
export function useMemoDrag(
  memoEls: MutableRefObject<Map<string, SVGGElement>>,
  editMode: boolean,
): (id: string, e: React.MouseEvent<SVGGElement>) => void {
  return useCallback(
    (id, e) => {
      if (!editMode || e.button !== 0) {
        return;
      }
      const el = memoEls.current.get(id);
      if (!el) {
        return;
      }
      const user = erdSocket.currentUser();
      beginDrag(el, e, {
        moveKey: `memo:${id}`,
        centerOffsetY: 0,
        makeOp: (x, y) => ({ type: "memo.move", user, payload: { id, x, y } }),
      });
    },
    [editMode, memoEls],
  );
}
