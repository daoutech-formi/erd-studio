import { memo } from "react";

interface Props {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  hl: boolean;
  dim: boolean;
}

/** 관계 곡선 1개 (child 상단 → parent 상단 베지어). */
export const ErdEdge = memo(function ErdEdge({ x1, y1, x2, y2, hl, dim }: Props) {
  const mx = (x1 + x2) / 2;
  const cls = `edge${hl ? " hl" : ""}${dim ? " dim" : ""}`;
  return <path className={cls} d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`} />;
});
