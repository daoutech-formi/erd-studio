import { useState } from "react";
import { erdSocket } from "../api/socket";
import { useDispatch, useStore } from "../state/schemaStore";
import { invertOp } from "../state/undo";
import type { Op, Row } from "../types";
import { rowStr } from "../types";

interface Props {
  onClose: () => void;
}

interface DomainDraft {
  key: string;
  name: string;
  color: string;
}

const MAX_DOMAINS = 20;
const PALETTE = [
  "#4f8cff", "#37c98b", "#ff7a7a", "#ffb454", "#c17aff",
  "#00c2d1", "#e86ab0", "#8ab0d0", "#7fd3a8", "#ffd479", "#6bd0e0", "#9aa0aa",
];

/** 새 도메인 키 — 기존/작성 중인 키와 겹치지 않는 d1, d2 … */
function nextKey(taken: Set<string>): string {
  for (let i = 1; i <= MAX_DOMAINS * 2; i += 1) {
    if (!taken.has(`d${i}`)) {
      return `d${i}`;
    }
  }
  return `d${Date.now()}`;
}

/** 도메인 추가·이름/색상 변경·삭제·순서 변경을 한 번에 적용하는 모달. */
export function DomainModal({ onClose }: Props) {
  const { doc } = useStore();
  const dispatch = useDispatch();
  const [drafts, setDrafts] = useState<DomainDraft[]>(() =>
    Object.entries(doc?.domains ?? {}).map(([key, d]) => ({ key, name: d.name, color: d.color })),
  );
  const [error, setError] = useState("");

  if (!doc) {
    return null;
  }

  /** 도메인별 테이블 수 — 삭제 시 영향 범위를 보여준다. */
  const countOf = (key: string) => doc.tables.filter((t) => rowStr(t, 1) === key).length;

  const setAt = (i: number, patch: Partial<DomainDraft>) =>
    setDrafts(drafts.map((d, j) => (j === i ? { ...d, ...patch } : d)));

  const add = () => {
    if (drafts.length >= MAX_DOMAINS) {
      setError(`도메인은 최대 ${MAX_DOMAINS}개까지 만들 수 있습니다.`);
      return;
    }
    const taken = new Set(drafts.map((d) => d.key));
    setError("");
    setDrafts([...drafts, { key: nextKey(taken), name: "", color: PALETTE[drafts.length % PALETTE.length] }]);
  };

  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= drafts.length) {
      return;
    }
    const next = [...drafts];
    [next[i], next[j]] = [next[j], next[i]];
    setDrafts(next);
  };

  const removeAt = (i: number) => {
    const target = drafts[i];
    const used = countOf(target.key);
    const fallback = drafts.find((_, j) => j !== i);
    if (!fallback) {
      setError("도메인은 최소 1개 이상이어야 합니다.");
      return;
    }
    if (used > 0 && !window.confirm(
      `'${target.name || target.key}' 도메인에 테이블 ${used}개가 있습니다.\n` +
      `삭제하면 이 테이블들은 '${fallback.name || fallback.key}' 도메인으로 옮겨집니다. 계속할까요?`,
    )) {
      return;
    }
    setError("");
    setDrafts(drafts.filter((_, j) => j !== i));
  };

  const apply = () => {
    if (drafts.length === 0) {
      setError("도메인은 최소 1개 이상이어야 합니다.");
      return;
    }
    if (drafts.some((d) => !d.name.trim())) {
      setError("도메인 이름을 모두 입력하세요.");
      return;
    }
    const names = drafts.map((d) => d.name.trim());
    if (new Set(names).size !== names.length) {
      setError("도메인 이름이 중복되었습니다.");
      return;
    }
    const rows: Row[] = drafts.map((d) => [d.key, d.name.trim(), d.color]);
    const op: Op = { type: "domain.apply", user: erdSocket.currentUser(), payload: { domains: rows } };
    erdSocket.sendEditOp(op, invertOp(op, doc, erdSocket.currentUser()));
    dispatch({ type: "toast", toast: { message: "도메인이 적용되었습니다.", kind: "ok" } });
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" style={{ width: 520, maxWidth: "94vw" }}>
        <h2>🎨 도메인 관리</h2>
        <p>
          도메인을 추가·수정·삭제하고 순서를 바꿉니다. 순서는 범례와 자동 배치에 그대로 반영됩니다.
          삭제한 도메인의 테이블은 목록 맨 위 도메인으로 옮겨집니다.
        </p>

        <div className="domain-rows">
          {drafts.map((d, i) => (
            <div className="domain-row" key={d.key}>
              <input
                type="color"
                className="domain-color"
                value={d.color}
                onChange={(e) => setAt(i, { color: e.target.value })}
                title="색상"
              />
              <input
                className="ci"
                style={{ flex: 1 }}
                value={d.name}
                placeholder="도메인 이름 (예: 회원/인증)"
                onChange={(e) => setAt(i, { name: e.target.value })}
              />
              <span className="domain-count">{countOf(d.key)}개</span>
              <button className="mini" onClick={() => move(i, -1)} disabled={i === 0} title="위로">↑</button>
              <button className="mini" onClick={() => move(i, 1)} disabled={i === drafts.length - 1} title="아래로">↓</button>
              <button className="mini danger" onClick={() => removeAt(i)} title="삭제">×</button>
            </div>
          ))}
        </div>

        <button className="mini" style={{ marginTop: 8 }} onClick={add}>＋ 도메인 추가</button>
        {error && <p style={{ color: "#ff9f9f" }}>{error}</p>}

        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button className="mini primary" onClick={apply}>적용</button>
          <button className="mini" onClick={onClose}>닫기</button>
          <span className="tbnote" style={{ alignSelf: "center" }}>
            {drafts.length} / {MAX_DOMAINS}
          </span>
        </div>
        <div className="hint">적용 즉시 모든 접속자에게 반영되고 DB에 저장됩니다. Ctrl+Z로 되돌릴 수 있습니다.</div>
      </div>
    </div>
  );
}
