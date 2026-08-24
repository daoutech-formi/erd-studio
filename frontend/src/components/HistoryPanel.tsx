import { useCallback, useEffect, useState } from "react";
import { fetchHistory, restoreHistory } from "../api/http";
import { erdSocket } from "../api/socket";
import { useDispatch } from "../state/schemaStore";
import { undoManager } from "../state/undo";
import type { HistoryEntry } from "../types";
import { opLabel } from "../utils/opLabel";
import { HistoryDiffModal } from "./HistoryDiffModal";

interface Props {
  roomId: number;
}

/** 현재 방의 변경 이력 목록 + 시점별 변경 내용 비교 + 특정 시점 복원. */
export function HistoryPanel({ roomId }: Props) {
  const dispatch = useDispatch();
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [diffEntry, setDiffEntry] = useState<HistoryEntry | null>(null);
  const [error, setError] = useState("");

  const reload = useCallback(() => {
    fetchHistory(roomId, 50)
      .then(setEntries)
      .catch((e: Error) => setError(e.message));
  }, [roomId]);

  useEffect(reload, [reload]);

  const restore = (entry: HistoryEntry) => {
    if (!window.confirm(`#${entry.id} (${opLabel(entry.opKind)} · ${entry.target}) 시점으로 복원할까요?`)) {
      return;
    }
    restoreHistory(roomId, entry.id, erdSocket.currentUser())
      .then(() => {
        undoManager.clear();
        dispatch({ type: "toast", toast: { message: "복원되었습니다.", kind: "ok" } });
        reload();
      })
      .catch((e: Error) => dispatch({ type: "toast", toast: { message: `복원 실패: ${e.message}`, kind: "err" } }));
  };

  return (
    <div className="history-panel">
      <div className="panel-head">
        <h3>변경 이력</h3>
        <span>
          <button className="mini" onClick={reload}>새로고침</button>{" "}
          <button className="mini" onClick={() => dispatch({ type: "historyOpen", on: false })}>×</button>
        </span>
      </div>
      {error && <div className="hint">불러오기 실패: {error}</div>}
      <table>
        <thead>
          <tr><th>시각</th><th>사용자</th><th>작업</th><th>대상</th><th /></tr>
        </thead>
        <tbody>
          {entries.map((h) => (
            <tr key={h.id}>
              <td>{new Date(h.createdAt).toLocaleTimeString("ko-KR", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" })}</td>
              <td>{h.userName}</td>
              <td>{opLabel(h.opKind)}</td>
              <td>{h.target}</td>
              <td>
                <button className="mini" onClick={() => setDiffEntry(h)}>비교</button>{" "}
                <button className="mini" onClick={() => restore(h)}>복원</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="hint">비교는 직전 이력과의 차이를 보여주고, 복원하면 그 시점의 전체 스키마로 되돌아가며 복원 자체도 이력에 남습니다.</div>
      {diffEntry && <HistoryDiffModal roomId={roomId} entry={diffEntry} onClose={() => setDiffEntry(null)} />}
    </div>
  );
}
