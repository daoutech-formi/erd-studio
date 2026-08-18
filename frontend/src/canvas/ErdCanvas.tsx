import { useCallback, useMemo, useRef, useState } from "react";
import type { MutableRefObject } from "react";
import { MemoModal } from "../components/MemoModal";
import { useDispatch, useStore } from "../state/schemaStore";
import { docMemos, rowNum, rowStr, rowStrArr, MEMO_DEFAULT_COLOR } from "../types";
import { ErdEdge } from "./ErdEdge";
import { ErdNode } from "./ErdNode";
import { MemoNode } from "./MemoNode";
import { Minimap } from "./Minimap";
import { computeLayout, type LayoutResult } from "./layout";
import { usePanZoom, type PanZoomApi } from "./usePanZoom";
import { useMemoDrag, useNodeDrag, wasJustDragged } from "./useNodeDrag";

interface Props {
  apiRef: MutableRefObject<PanZoomApi | null>;
  onNodeClick: (name: string) => void;
}

const EMPTY_LAYOUT: LayoutResult = { nodes: {}, labels: [], countByDomain: {} };

/** SVG 캔버스 전체 — 레이아웃·강조 계산과 노드/엣지 나열만 담당한다. */
export function ErdCanvas({ apiRef, onNodeClick }: Props) {
  const { doc, selected, selectedMemo, search, focusDomain, editMode, locks, editing } = useStore();
  const dispatch = useDispatch();
  const stageRef = useRef<HTMLDivElement>(null);
  const viewportRef = useRef<SVGGElement>(null);
  const nodeEls = useRef(new Map<string, SVGGElement>());
  const memoEls = useRef(new Map<string, SVGGElement>());
  /** 편집 중인 메모 id — 편집 모드에서 메모를 클릭하면 모달이 열린다. */
  const [memoOpen, setMemoOpen] = useState<string | null>(null);

  const layout = useMemo(() => (doc ? computeLayout(doc) : EMPTY_LAYOUT), [doc]);

  const adjacency = useMemo(() => {
    const adj = new Map<string, Set<string>>();
    if (!doc) {
      return adj;
    }
    for (const r of doc.relations) {
      const c = rowStr(r, 0);
      const p = rowStr(r, 1);
      (adj.get(c) ?? adj.set(c, new Set()).get(c))?.add(p);
      (adj.get(p) ?? adj.set(p, new Set()).get(p))?.add(c);
    }
    return adj;
  }, [doc]);

  /** 메모 id → 연결 테이블명 배열. */
  const memoLinks = useMemo(() => {
    const map = new Map<string, string[]>();
    if (doc) {
      for (const m of docMemos(doc)) {
        map.set(rowStr(m, 0), rowStrArr(m, 5));
      }
    }
    return map;
  }, [doc]);

  /** 강조 중인 메모에 연결된 테이블명 집합 — 없으면 null. */
  const memoLinked = useMemo(() => {
    const links = selectedMemo !== null ? memoLinks.get(selectedMemo) ?? [] : [];
    return links.length > 0 ? new Set(links) : null;
  }, [selectedMemo, memoLinks]);

  const searchMatches = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q || !doc) {
      return null;
    }
    const matches = new Set<string>();
    for (const t of doc.tables) {
      const name = rowStr(t, 0);
      const cols = doc.columns[name] ?? [];
      const hit =
        name.toLowerCase().includes(q) ||
        cols.some((c) => rowStr(c, 0).toLowerCase().includes(q) || rowStr(c, 2).toLowerCase().includes(q));
      if (hit) {
        matches.add(name);
      }
    }
    return matches;
  }, [doc, search]);

  const isNodeDim = useCallback(
    (name: string, domain: string): boolean => {
      if (searchMatches) {
        return !searchMatches.has(name);
      }
      if (focusDomain) {
        return domain !== focusDomain;
      }
      if (memoLinked) {
        return !memoLinked.has(name);
      }
      if (selected) {
        return name !== selected && !adjacency.get(selected)?.has(name);
      }
      return false;
    },
    [searchMatches, focusDomain, memoLinked, selected, adjacency],
  );

  const registerEl = useCallback((name: string, el: SVGGElement | null) => {
    if (el) {
      nodeEls.current.set(name, el);
    } else {
      nodeEls.current.delete(name);
    }
  }, []);

  const registerMemoEl = useCallback((id: string, el: SVGGElement | null) => {
    if (el) {
      memoEls.current.set(id, el);
    } else {
      memoEls.current.delete(id);
    }
  }, []);

  const openMemo = useCallback((id: string) => {
    if (!wasJustDragged()) {
      setMemoOpen(id);
    }
  }, []);

  /** 조회 모드 메모 클릭 — 링크가 있으면 강조 토글, 없으면 기존 강조만 해제한다. */
  const selectMemoClick = useCallback(
    (id: string) => {
      if (wasJustDragged()) {
        return;
      }
      const hasLinks = (memoLinks.get(id) ?? []).length > 0;
      dispatch({ type: "selectMemo", id: hasLinks && id !== selectedMemo ? id : null });
    },
    [dispatch, memoLinks, selectedMemo],
  );

  const clearFocus = useCallback(() => {
    dispatch({ type: "select", name: null });
  }, [dispatch]);

  const panZoom = usePanZoom(stageRef, viewportRef, clearFocus);
  apiRef.current = panZoom;
  const onNodeMouseDown = useNodeDrag(nodeEls, editMode);
  const onMemoMouseDown = useMemoDrag(memoEls, editMode);

  /** 메모별 흐림 — 강조 중인 메모·선택 테이블에 연결된 메모는 남기고 나머지를 흐린다. */
  const isMemoDim = useCallback(
    (id: string, links: string[]): boolean => {
      if (selectedMemo !== null) {
        return id !== selectedMemo;
      }
      if (searchMatches !== null || focusDomain !== null) {
        return true;
      }
      if (selected !== null) {
        return !links.includes(selected);
      }
      return false;
    },
    [selectedMemo, searchMatches, focusDomain, selected],
  );

  return (
    <div id="stage" ref={stageRef}>
      <svg id="svg">
        <g id="viewport" ref={viewportRef}>
          <g id="edges">
            {doc?.relations.map((r, i) => {
              const cn = layout.nodes[rowStr(r, 0)];
              const pn = layout.nodes[rowStr(r, 1)];
              if (!cn || !pn) {
                return null;
              }
              const hl =
                (selected !== null && (cn.name === selected || pn.name === selected)) ||
                (memoLinked !== null && memoLinked.has(cn.name) && memoLinked.has(pn.name));
              const dim =
                !hl &&
                (selected !== null || searchMatches !== null || focusDomain !== null || memoLinked !== null);
              return (
                <ErdEdge
                  key={`${cn.name}→${pn.name}#${i}`}
                  x1={cn.x + 75}
                  y1={cn.y}
                  x2={pn.x + 75}
                  y2={pn.y}
                  cardinality={rowStr(r, 3)}
                  hl={hl}
                  dim={dim}
                />
              );
            })}
          </g>
          <g id="nodes">
            {layout.labels.map((l) =>
              layout.countByDomain[l.key] > 0 ? (
                <text key={l.key} x={l.x} y={l.y} className="domain-label" fill={l.color}>
                  ● {l.name}
                </text>
              ) : null,
            )}
            {Object.values(layout.nodes).map((n) => {
              const lock = locks[n.name];
              const lockedByOther = lock !== undefined && n.name !== editing;
              return (
                <ErdNode
                  key={n.name}
                  name={n.name}
                  desc={n.desc}
                  color={doc?.domains[n.domain]?.color ?? "#888"}
                  hub={n.hub}
                  x={n.x}
                  y={n.y}
                  dim={isNodeDim(n.name, n.domain)}
                  selected={n.name === selected}
                  lockUser={lockedByOther ? lock.user : null}
                  lockColor={lockedByOther ? lock.color : null}
                  onSelect={onNodeClick}
                  onNodeMouseDown={onNodeMouseDown}
                  registerEl={registerEl}
                />
              );
            })}
          </g>
          <g id="memos">
            {doc && docMemos(doc).map((m) => {
              const id = rowStr(m, 0);
              const links = memoLinks.get(id) ?? [];
              return (
                <MemoNode
                  key={id}
                  id={id}
                  text={rowStr(m, 1)}
                  color={rowStr(m, 4) || MEMO_DEFAULT_COLOR}
                  x={rowNum(m, 2) ?? 0}
                  y={rowNum(m, 3) ?? 0}
                  dim={isMemoDim(id, links)}
                  linkCount={links.length}
                  active={id === selectedMemo}
                  editMode={editMode}
                  onOpen={openMemo}
                  onSelect={selectMemoClick}
                  onMemoMouseDown={onMemoMouseDown}
                  registerEl={registerMemoEl}
                />
              );
            })}
          </g>
        </g>
      </svg>
      <Minimap layout={layout} domains={doc?.domains} api={panZoom} stageRef={stageRef} />
      <div className="footnote">마우스 드래그: 이동 · 휠: 확대/축소 · 노드 클릭: 관계 강조 · 메모 클릭: 연결 테이블 강조{editMode ? " · 편집 모드: 노드 드래그로 위치 이동, 메모 클릭으로 편집" : ""}</div>
      {memoOpen && <MemoModal id={memoOpen} onClose={() => setMemoOpen(null)} />}
    </div>
  );
}
