import { memo, useEffect, useMemo, useRef, useState, type RefObject } from "react";
import type { DomainDef } from "../types";
import { NODE_H, NODE_W, type LayoutResult } from "./layout";
import type { PanZoomApi, ViewState } from "./usePanZoom";

const W = 200;
const H = 140;
const PAD = 8;

interface Props {
  layout: LayoutResult;
  domains: Record<string, DomainDef> | undefined;
  api: PanZoomApi;
  stageRef: RefObject<HTMLDivElement>;
}

interface Geometry {
  bx: number;
  by: number;
  scale: number;
  ox: number;
  oy: number;
}

/** 우하단 미니맵 — 전체 노드 축소 뷰 + 현재 뷰포트 사각형 + 클릭/드래그 이동. */
export const Minimap = memo(function Minimap({ layout, domains, api, stageRef }: Props) {
  const [view, setView] = useState<ViewState>(() => api.getView());
  const boxRef = useRef<HTMLDivElement>(null);
  /** 마우스 핸들러(네이티브)가 렌더 시점의 최신 매핑을 쓰도록 ref에 담아 둔다. */
  const geoRef = useRef<Geometry | null>(null);

  useEffect(() => api.subscribe(setView), [api]);

  const nodes = useMemo(() => Object.values(layout.nodes), [layout]);
  const bounds = useMemo(() => {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const n of nodes) {
      minX = Math.min(minX, n.x);
      minY = Math.min(minY, n.y - NODE_H / 2);
      maxX = Math.max(maxX, n.x + NODE_W);
      maxY = Math.max(maxY, n.y + NODE_H / 2);
    }
    return { x: minX, y: minY, w: Math.max(maxX - minX, 1), h: Math.max(maxY - minY, 1) };
  }, [nodes]);

  const s = Math.min((W - PAD * 2) / bounds.w, (H - PAD * 2) / bounds.h);
  const ox = (W - bounds.w * s) / 2;
  const oy = (H - bounds.h * s) / 2;
  geoRef.current = { bx: bounds.x, by: bounds.y, scale: s, ox, oy };

  // 노드가 없는 동안은 null 을 렌더하므로, div 가 생기는 시점에 맞춰 리스너를 다시 붙인다.
  const hasNodes = nodes.length > 0;

  // 스테이지 팬(네이티브 mousedown)이 함께 동작하지 않도록 네이티브 리스너로 처리한다.
  useEffect(() => {
    const box = boxRef.current;
    if (!box) {
      return;
    }
    const centerAt = (e: MouseEvent) => {
      const geo = geoRef.current;
      if (!geo) {
        return;
      }
      const rect = box.getBoundingClientRect();
      const wx = (e.clientX - rect.left - geo.ox) / geo.scale + geo.bx;
      const wy = (e.clientY - rect.top - geo.oy) / geo.scale + geo.by;
      api.centerOn(wx, wy);
    };
    let dragging = false;
    const onDown = (e: MouseEvent) => {
      e.stopPropagation();
      e.preventDefault();
      dragging = true;
      centerAt(e);
    };
    const onMove = (e: MouseEvent) => {
      if (dragging) {
        centerAt(e);
      }
    };
    const onUp = () => {
      dragging = false;
    };
    const onWheel = (e: WheelEvent) => e.stopPropagation();
    box.addEventListener("mousedown", onDown);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    box.addEventListener("wheel", onWheel);
    return () => {
      box.removeEventListener("mousedown", onDown);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      box.removeEventListener("wheel", onWheel);
    };
  }, [api, hasNodes]);

  if (nodes.length === 0) {
    return null;
  }

  const mapX = (wx: number): number => ox + (wx - bounds.x) * s;
  const mapY = (wy: number): number => oy + (wy - bounds.y) * s;

  // 현재 화면에 보이는 월드 영역 — 스테이지 크기 기준.
  const stageW = stageRef.current?.clientWidth ?? 0;
  const stageH = stageRef.current?.clientHeight ?? 0;
  const vx = mapX((0 - view.tx) / view.scale);
  const vy = mapY((0 - view.ty) / view.scale);
  const vw = (stageW / view.scale) * s;
  const vh = (stageH / view.scale) * s;

  return (
    <div className="minimap" ref={boxRef} title="클릭/드래그로 이동">
      <svg width={W} height={H}>
        {nodes.map((n) => (
          <rect
            key={n.name}
            x={mapX(n.x)}
            y={mapY(n.y - NODE_H / 2)}
            width={Math.max(NODE_W * s, 2)}
            height={Math.max(NODE_H * s, 1.5)}
            rx={1}
            fill={domains?.[n.domain]?.color ?? "#888"}
            opacity={0.75}
          />
        ))}
        {stageW > 0 && <rect className="mini-viewport" x={vx} y={vy} width={vw} height={vh} rx={2} />}
      </svg>
    </div>
  );
});
