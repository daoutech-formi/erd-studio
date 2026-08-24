import { useEffect, useState } from "react";
import { fetchHistoryDiff } from "../api/http";
import type { HistoryEntry } from "../types";
import { opLabel } from "../utils/opLabel";
import { diffSchemaDocs, type ChangeKind, type SchemaDiffResult, type TableDiff } from "../utils/schemaDiff";

interface Props {
  roomId: number;
  entry: HistoryEntry;
  onClose: () => void;
}

const KIND_MARK: Record<ChangeKind, { sign: string; cls: string }> = {
  added: { sign: "＋", cls: "add" },
  removed: { sign: "－", cls: "del" },
  changed: { sign: "△", cls: "upd" },
};

const arrow = (from: string, to: string) => `${from || "(없음)"} → ${to || "(없음)"}`;

function TableDiffRow({ t }: { t: TableDiff }) {
  const mark = KIND_MARK[t.kind];
  return (
    <li className={`diff-item ${mark.cls}`}>
      <span className="diff-sign">{mark.sign}</span>
      <div>
        <b>{t.name}</b>
        {t.kind === "added" && <span className="diff-detail">테이블 추가</span>}
        {t.kind === "removed" && <span className="diff-detail">테이블 삭제</span>}
        {t.moved && <span className="diff-detail">위치 이동</span>}
        {t.changes.map((c) => (
          <div className="diff-sub" key={c.label}>{c.label}: {arrow(c.from, c.to)}</div>
        ))}
        {t.columns.map((c) => {
          const m = KIND_MARK[c.kind];
          return (
            <div className={`diff-sub ${m.cls}`} key={`${c.kind}:${c.name}`}>
              {m.sign} 컬럼 <b>{c.name}</b>
              {c.summary && <> — {c.summary}</>}
              {c.changes?.map((ch) => (
                <span key={ch.label}> · {ch.label} {arrow(ch.from, ch.to)}</span>
              ))}
            </div>
          );
        })}
      </div>
    </li>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="diff-section">
      <div className="diff-section-title">{title}</div>
      <ul className="diff-list">{children}</ul>
    </div>
  );
}

/** 선택한 이력과 직전 이력의 스냅샷을 비교해 무엇이 바뀌었는지 보여주는 모달. */
export function HistoryDiffModal({ roomId, entry, onClose }: Props) {
  const [diff, setDiff] = useState<SchemaDiffResult | null>(null);
  const [firstEntry, setFirstEntry] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    fetchHistoryDiff(roomId, entry.id)
      .then((d) => {
        setFirstEntry(d.before === null);
        setDiff(diffSchemaDocs(d.before, d.after));
      })
      .catch((e: Error) => setError(e.message));
  }, [roomId, entry.id]);

  return (
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal diff-modal">
        <h2>변경 내용 비교 — #{entry.id}</h2>
        <p>
          {new Date(entry.createdAt).toLocaleString("ko-KR", { hour12: false })} · {entry.userName} ·{" "}
          {opLabel(entry.opKind)} · {entry.target}
          {firstEntry && " (이 방의 첫 이력 — 전체가 추가로 표시됩니다)"}
        </p>
        {error && <div className="hint">불러오기 실패: {error}</div>}
        {!error && diff === null && <div className="hint">불러오는 중…</div>}
        {diff && diff.empty && <div className="hint">직전 이력과 차이가 없습니다.</div>}
        {diff && !diff.empty && (
          <div className="diff-body">
            {diff.domains.length > 0 && (
              <Section title="도메인">
                {diff.domains.map((d) => (
                  <li className={`diff-item ${KIND_MARK[d.kind].cls}`} key={`${d.kind}:${d.key}`}>
                    <span className="diff-sign">{KIND_MARK[d.kind].sign}</span>
                    <div><b>{d.key}</b>{d.detail && <span className="diff-detail">{d.detail}</span>}</div>
                  </li>
                ))}
              </Section>
            )}
            {diff.tables.length > 0 && (
              <Section title="테이블">
                {diff.tables.map((t) => <TableDiffRow t={t} key={`${t.kind}:${t.name}`} />)}
              </Section>
            )}
            {diff.relations.length > 0 && (
              <Section title="관계">
                {diff.relations.map((r) => (
                  <li className={`diff-item ${KIND_MARK[r.kind].cls}`} key={`${r.kind}:${r.key}`}>
                    <span className="diff-sign">{KIND_MARK[r.kind].sign}</span>
                    <div><b>{r.key}</b>{r.detail && <span className="diff-detail">{r.detail}</span>}</div>
                  </li>
                ))}
              </Section>
            )}
            {diff.memos.length > 0 && (
              <Section title="메모">
                {diff.memos.map((m, i) => (
                  <li className={`diff-item ${KIND_MARK[m.kind].cls}`} key={`${m.kind}:${m.key}:${i}`}>
                    <span className="diff-sign">{KIND_MARK[m.kind].sign}</span>
                    <div><b>{m.key}</b>{m.detail && <span className="diff-detail">{m.detail}</span>}</div>
                  </li>
                ))}
              </Section>
            )}
          </div>
        )}
        <div className="modal-actions" style={{ marginTop: 14 }}>
          <button onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  );
}
