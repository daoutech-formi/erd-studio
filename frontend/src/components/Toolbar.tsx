import { useState } from "react";
import { putSchema } from "../api/http";
import { erdSocket } from "../api/socket";
import { exportDbml } from "../exporters/dbml";
import { exportPng, exportSvg } from "../exporters/image";
import { exportJson, importJson } from "../exporters/json";
import { exportSql } from "../exporters/sql";
import { useDispatch, useStore } from "../state/schemaStore";
import { invertOp, undoManager } from "../state/undo";
import type { Op } from "../types";
import { rowStr } from "../types";
import { DdlImportModal } from "./DdlImportModal";

/** 편집 모드 툴바 — 테이블 추가, Undo/Redo, 변경 이력. */
export function Toolbar() {
  const { doc, historyOpen } = useStore();
  const dispatch = useDispatch();
  const [ddlOpen, setDdlOpen] = useState(false);
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

  const toastErr = (message: string) => dispatch({ type: "toast", toast: { message, kind: "err" } });

  const doImport = () => {
    importJson((newDoc) => {
      if (!window.confirm("불러온 JSON으로 전체 스키마를 교체할까요? (모든 접속자에게 반영)")) {
        return;
      }
      undoManager.push({
        undo: [{ type: "schema.replace", user, payload: { doc } }],
        redo: [{ type: "schema.replace", user, payload: { doc: newDoc } }],
      });
      putSchema(newDoc, user)
        .then((r) => dispatch({
          type: "toast",
          toast: { message: `불러오기 완료 (테이블 ${r.tables}개, 관계 ${r.relations}개)`, kind: "ok" },
        }))
        .catch((e: Error) => {
          undoManager.popLast();
          toastErr(`불러오기 실패: ${e.message}`);
        });
    }, toastErr);
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
      <span className="tbsep" />
      <button onClick={() => exportJson(doc)}>💾 JSON 저장</button>
      <button onClick={doImport}>📂 JSON 불러오기</button>
      <button onClick={() => setDdlOpen(true)}>⬆ DDL 불러오기</button>
      <button onClick={() => exportSql(doc)}>⬇ SQL</button>
      <button onClick={() => exportDbml(doc)}>⬇ DBML</button>
      <button onClick={() => exportPng(toastErr)}>🖼 PNG</button>
      <button onClick={() => exportSvg(toastErr)}>🖼 SVG</button>
      <span className="tbnote">모든 편집은 즉시 전체 접속자에게 반영되고 DB에 저장됩니다</span>
      {ddlOpen && <DdlImportModal onClose={() => setDdlOpen(false)} />}
    </div>
  );
}
