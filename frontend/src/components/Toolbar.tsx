import { erdSocket } from "../api/socket";
import { useDispatch, useStore } from "../state/schemaStore";
import { invertOp, undoManager } from "../state/undo";
import type { Op } from "../types";
import { rowStr } from "../types";

/** 편집 모드 툴바 — 테이블 추가, Undo/Redo, 변경 이력. */
export function Toolbar() {
  const { doc, historyOpen } = useStore();
  const dispatch = useDispatch();
  if (!doc) {
    return null;
  }
  const user = erdSocket.currentUser();

  const addTable = () => {
    const name = window.prompt("새 테이블명을 입력하세요:", "new_table")?.trim();
    if (!name) {
      return;
    }
    if (doc.tables.some((t) => rowStr(t, 0) === name)) {
      dispatch({ type: "toast", toast: { message: "이미 존재하는 테이블명입니다.", kind: "err" } });
      return;
    }
    const domain = doc.domains.stat ? "stat" : Object.keys(doc.domains)[0];
    const op: Op = { type: "table.add", user, payload: { name, domain, desc: "새 테이블" } };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
    dispatch({ type: "select", name });
  };

  const run = (ops: Op[] | null) => {
    if (!ops || ops.length === 0) {
      return;
    }
    ops.forEach((op) => erdSocket.sendOp(op));
  };

  return (
    <div className="toolbar">
      <button onClick={addTable}>＋ 테이블 추가</button>
      <span className="tbsep" />
      <button onClick={() => run(undoManager.undo())}>↺ 되돌리기</button>
      <button onClick={() => run(undoManager.redo())}>↻ 다시실행</button>
      <span className="tbsep" />
      <button className={historyOpen ? "on" : ""} onClick={() => dispatch({ type: "historyOpen", on: !historyOpen })}>
        🕘 변경 이력
      </button>
      <span className="tbnote">모든 편집은 즉시 전체 접속자에게 반영되고 DB에 저장됩니다</span>
    </div>
  );
}
