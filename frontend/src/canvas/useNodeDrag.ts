import { useCallback, type MutableRefObject } from "react";
import { erdSocket } from "../api/socket";
import { NODE_H } from "./layout";

const MOVE_THROTTLE_MS = 50;
const TRANSLATE_RE = /translate\(([-\d.]+),([-\d.]+)\)/;

/**
 * 편집 모드에서 노드를 드래그해 옮긴다.
 * 드래그 중에는 DOM transform만 갱신(+50ms throttle로 move 중계)하고,
 * mouseup 시에만 table.move op를 보내 DB에 확정한다.
 */
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
      e.stopPropagation();
      e.preventDefault();
      const scale = el.getScreenCTM()?.a ?? 1;
      const match = TRANSLATE_RE.exec(el.getAttribute("transform") ?? "");
      const baseX = match ? Number(match[1]) : 0;
      const baseTopY = match ? Number(match[2]) : 0;
      const startCX = e.clientX;
      const startCY = e.clientY;
      const startCenter = { x: baseX, y: baseTopY + NODE_H / 2 };
      let current = { ...startCenter };
      let moved = false;
      let lastSent = 0;

      const onMove = (ev: MouseEvent) => {
        const x = baseX + (ev.clientX - startCX) / scale;
        const topY = baseTopY + (ev.clientY - startCY) / scale;
        current = { x, y: topY + NODE_H / 2 };
        moved = true;
        el.setAttribute("transform", `translate(${x},${topY})`);
        const now = performance.now();
        if (now - lastSent >= MOVE_THROTTLE_MS) {
          lastSent = now;
          erdSocket.sendMove(name, current.x, current.y);
        }
      };
      const onUp = () => {
        window.removeEventListener("mousemove", onMove);
        window.removeEventListener("mouseup", onUp);
        if (!moved) {
          return;
        }
        const user = erdSocket.currentUser();
        erdSocket.sendEditOp(
          { type: "table.move", user, payload: { name, x: current.x, y: current.y } },
          [{ type: "table.move", user, payload: { name, x: startCenter.x, y: startCenter.y } }],
        );
      };
      window.addEventListener("mousemove", onMove);
      window.addEventListener("mouseup", onUp);
    },
    [editMode, nodeEls],
  );
}
