import { memo } from "react";

export const MEMO_W = 170;
const PAD_X = 9;
const LINE_H = 15;
/** 전각(한글 등)/반각 문자의 대략적 폭 — 12px 폰트 기준. */
const WIDE_CH = 12;
const NARROW_CH = 6.4;
const MAX_LINE_W = MEMO_W - PAD_X * 2;

interface Props {
  id: string;
  text: string;
  color: string;
  x: number;
  y: number;
  dim: boolean;
  /** 연결된 테이블 개수 — 1개 이상이면 우상단에 링크 배지를 그린다. */
  linkCount: number;
  /** 조회 모드에서 클릭해 강조 중인 메모인지. */
  active: boolean;
  editMode: boolean;
  onOpen: (id: string) => void;
  onSelect: (id: string) => void;
  onMemoMouseDown: (id: string, e: React.MouseEvent<SVGGElement>) => void;
  registerEl: (id: string, el: SVGGElement | null) => void;
}

/** 개행 유지 + 폭 기준 자동 줄바꿈. SVG text에는 wrap이 없어 직접 자른다(이미지 내보내기 호환). */
export function wrapMemoLines(text: string): string[] {
  const out: string[] = [];
  for (const raw of text.split("\n")) {
    let line = "";
    let w = 0;
    for (const ch of raw) {
      const cw = ch.charCodeAt(0) > 0x2e7f ? WIDE_CH : NARROW_CH;
      if (w + cw > MAX_LINE_W && line) {
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

/** 캔버스 스티키 메모 1개. 좌표(x,y)는 좌상단 기준. */
export const MemoNode = memo(function MemoNode(props: Props) {
  const { id, text, color, x, y, dim, linkCount, active, editMode } = props;
  const lines = wrapMemoLines(text.trim() === "" ? "(내용 없음)" : text);
  const h = Math.max(34, lines.length * LINE_H + 13);

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
      <rect width={MEMO_W} height={h} rx={3} fill={color} />
      <path className="memo-fold" d={`M${MEMO_W - 12} ${h} L${MEMO_W} ${h - 12} L${MEMO_W} ${h} Z`} />
      {lines.map((line, i) => (
        <text key={i} x={PAD_X} y={19 + i * LINE_H}>
          {line}
        </text>
      ))}
      {linkCount > 0 && (
        <g className="memo-badge" transform={`translate(${MEMO_W - 14},-5)`}>
          <rect x={-16} y={-9} width={32} height={18} rx={9} />
          <text y={4}>🔗{linkCount}</text>
        </g>
      )}
    </g>
  );
});
