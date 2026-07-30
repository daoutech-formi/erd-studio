import { useCallback, useEffect, useState } from "react";
import { fetchHistory, restoreHistory } from "../api/http";
import { erdSocket } from "../api/socket";
import { useDispatch } from "../state/schemaStore";
import { undoManager } from "../state/undo";
import type { HistoryEntry } from "../types";

interface Props {
  roomId: number;
}

/** 현재 방의 변경 이력 목록 + 특정 시점 복원. */
export function HistoryPanel({ roomId }: Props) {
  const dispatch = useDispatch();
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [error, setError] = useState("");

  const reload = useCallback(() => {
    fetchHistory(roomId, 50)
      .then(setEntries)
      .catch((e: Error) => setError(e.message));
  }, [roomId]);

  useEffect(reload, [reload]);

  const restore = (entry: HistoryEntry) => {
    if (!window.confirm(`#${entry.id} (${entry.opKind} · ${entry.target}) 시점으로 복원할까요?`)) {
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
              <td>{new Date(h.createdAt).toLocaleTimeString("ko-KR", { hour12: false })}</td>
              <td>{h.userName}</td>
              <td>{h.opKind}</td>
              <td>{h.target}</td>
              <td><button className="mini" onClick={() => restore(h)}>복원</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="hint">복원하면 그 시점의 전체 스키마로 되돌아가며, 복원 자체도 이력에 남습니다.</div>
    </div>
  );
}
