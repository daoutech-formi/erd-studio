import { memo, useState } from "react";
import { suppressNextClick } from "./useNodeDrag";

export const MEMO_W = 170;
/** 크기 조절 허용 범위 — 서버(OpService)의 검증 범위 안이어야 한다. */
export const MEMO_MIN_W = 90;
export const MEMO_MAX_W = 800;
export const MEMO_MIN_H = 34;
export const MEMO_MAX_H = 800;
const PAD_X = 9;
const LINE_H = 15;
/** 전각(한글 등)/반각 문자의 대략적 폭 — 12px 폰트 기준. */
const WIDE_CH = 12;
const NARROW_CH = 6.4;
/** 우하단 크기 조절 핸들의 히트 영역 한 변. */
const RESIZE_HIT = 16;

interface Props {
  id: string;
  text: string;
  color: string;
  x: number;
  y: number;
  /** 사용자 지정 크기 — null이면 기본 폭·내용 높이를 쓴다. */
  w: number | null;
  h: number | null;
  dim: boolean;
  /** 연결된 테이블 개수 — 1개 이상이면 우상단에 링크 배지를 그린다. */
  linkCount: number;
  /** 조회 모드에서 클릭해 강조 중인 메모인지. */
  active: boolean;
  editMode: boolean;
  onOpen: (id: string) => void;
  onSelect: (id: string) => void;
  onMemoMouseDown: (id: string, e: React.MouseEvent<SVGGElement>) => void;
  onResize: (id: string, w: number, h: number) => void;
  registerEl: (id: string, el: SVGGElement | null) => void;
}

const clamp = (v: number, min: number, max: number): number => Math.min(max, Math.max(min, v));

/** 개행 유지 + 폭 기준 자동 줄바꿈. SVG text에는 wrap이 없어 직접 자른다(이미지 내보내기 호환). */
export function wrapMemoLines(text: string, width: number = MEMO_W): string[] {
  const maxLineW = width - PAD_X * 2;
  const out: string[] = [];
  for (const raw of text.split("\n")) {
    let line = "";
    let w = 0;
    for (const ch of raw) {
      const cw = ch.charCodeAt(0) > 0x2e7f ? WIDE_CH : NARROW_CH;
      if (w + cw > maxLineW && line) {
        out.push(line);
        line = "";
        w = 0;
      }
      line += ch;
      w += cw;
    }
    out.push(line);
  }
  return out;
}

/** 캔버스 스티키 메모 1개. 좌표(x,y)는 좌상단 기준, 크기는 우하단 모서리 드래그로 조절한다. */
export const MemoNode = memo(function MemoNode(props: Props) {
  const { id, text, color, x, y, dim, linkCount, active, editMode } = props;
  /** 크기 조절 드래그 중의 임시 크기 — 확정(mouseup) 전까지는 로컬에서만 미리 보여준다. */
  const [resizing, setResizing] = useState<{ w: number; h: number } | null>(null);

  const width = clamp(resizing?.w ?? props.w ?? MEMO_W, MEMO_MIN_W, MEMO_MAX_W);
  const lines = wrapMemoLines(text.trim() === "" ? "(내용 없음)" : text, width);
  const contentH = Math.max(MEMO_MIN_H, lines.length * LINE_H + 13);
  // 내용보다 작게 줄이면 글자가 넘치므로 내용 높이를 하한으로 삼는다.
  const h = Math.max(contentH, resizing?.h ?? props.h ?? 0);

  const onResizeMouseDown = (e: React.MouseEvent<SVGRectElement>) => {
    if (!editMode || e.button !== 0) {
      return;
    }
    e.stopPropagation();
    e.preventDefault();
    const scale = e.currentTarget.getScreenCTM()?.a ?? 1;
    const startCX = e.clientX;
    const startCY = e.clientY;
    const start = { w: width, h };
    let last = start;
    const onMove = (ev: MouseEvent) => {
      last = {
        w: clamp(start.w + (ev.clientX - startCX) / scale, MEMO_MIN_W, MEMO_MAX_W),
        h: clamp(start.h + (ev.clientY - startCY) / scale, MEMO_MIN_H, MEMO_MAX_H),
      };
      setResizing(last);
    };
    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      setResizing(null);
      if (last.w !== start.w || last.h !== start.h) {
        suppressNextClick();
        props.onResize(id, Math.round(last.w), Math.round(last.h));
      }
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  return (
    <g
      ref={(el) => props.registerEl(id, el)}
      className={`memo-node${dim ? " dim" : ""}${active ? " active" : ""}`}
      transform={`translate(${x},${y})`}
      style={{ cursor: editMode ? "grab" : linkCount > 0 ? "pointer" : "default" }}
      onClick={(e) => {
        e.stopPropagation();
        if (editMode) {
          props.onOpen(id);
        } else {
          props.onSelect(id);
        }
      }}
      onMouseDown={(e) => props.onMemoMouseDown(id, e)}
    >
      <rect width={width} height={h} rx={3} fill={color} />
      <path className="memo-fold" d={`M${width - 12} ${h} L${width} ${h - 12} L${width} ${h} Z`} />
      {lines.map((line, i) => (
        <text key={i} x={PAD_X} y={19 + i * LINE_H}>
          {line}
        </text>
      ))}
      {linkCount > 0 && (
        <g className="memo-badge" transform={`translate(${width - 14},-5)`}>
          <rect x={-16} y={-9} width={32} height={18} rx={9} />
          <text y={4}>🔗{linkCount}</text>
        </g>
      )}
      {editMode && (
        <rect
          className="memo-resize"
          x={width - RESIZE_HIT}
          y={h - RESIZE_HIT}
          width={RESIZE_HIT}
          height={RESIZE_HIT}
          fill="transparent"
          style={{ cursor: "nwse-resize" }}
          onMouseDown={onResizeMouseDown}
        />
      )}
    </g>
  );
});
