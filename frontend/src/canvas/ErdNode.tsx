import { memo } from "react";
import { NODE_H, NODE_W } from "./layout";

declare global {
  interface Window {
    __nodeRenders?: number;
  }
}

interface Props {
  name: string;
  desc: string;
  color: string;
  hub: boolean;
  x: number;
  y: number;
  dim: boolean;
  selected: boolean;
  lockUser: string | null;
  lockColor: string | null;
  onSelect: (name: string) => void;
  onNodeMouseDown: (name: string, e: React.MouseEvent<SVGGElement>) => void;
  registerEl: (name: string, el: SVGGElement | null) => void;
}

const MAX_NAME_CHARS = 22;

/** 테이블 노드 1개. props가 모두 원시값이라 변경된 노드만 리렌더된다. */
export const ErdNode = memo(function ErdNode(props: Props) {
  if (import.meta.env.DEV) {
    window.__nodeRenders = (window.__nodeRenders ?? 0) + 1;
  }
  const { name, desc, color, hub, x, y, dim, selected, lockUser, lockColor } = props;
  const label = name.length > MAX_NAME_CHARS ? `${name.slice(0, MAX_NAME_CHARS - 1)}…` : name;
  const cls = `node${hub ? " hub" : ""}${dim ? " dim" : ""}${selected ? " sel" : ""}`;

  return (
    <g
      ref={(el) => props.registerEl(name, el)}
      className={cls}
      transform={`translate(${x},${y - NODE_H / 2})`}
      onClick={(e) => {
        e.stopPropagation();
        props.onSelect(name);
      }}
      onMouseDown={(e) => props.onNodeMouseDown(name, e)}
    >
      <rect
        width={NODE_W}
        height={NODE_H}
        stroke={lockColor ?? color}
        strokeWidth={lockUser ? 2 : 1}
        strokeDasharray={lockUser ? "4 3" : undefined}
        rx={4}
      />
      <text x={8} y={14}>{label}</text>
      <text x={8} y={27} className="key">{desc}</text>
      {lockUser && (
        <text x={0} y={-6} className="lock-badge" fill={lockColor ?? "#ffb454"}>
          {lockUser}님이 편집 중
        </text>
      )}
    </g>
  );
});
