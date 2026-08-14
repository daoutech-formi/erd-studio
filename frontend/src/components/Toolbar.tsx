import { useState } from "react";
import { putSchema } from "../api/http";
import { erdSocket } from "../api/socket";
import { exportDbml } from "../exporters/dbml";
import { exportPdf, exportPng, exportSvg } from "../exporters/image";
import { exportJson, importJson } from "../exporters/json";
import { exportSql } from "../exporters/sql";
import { useDispatch, useStore } from "../state/schemaStore";
import { invertOp, undoManager } from "../state/undo";
import type { Op } from "../types";
import { MEMO_DEFAULT_COLOR, docMemos, rowStr } from "../types";
import { DdlImportModal } from "./DdlImportModal";
import { DomainModal } from "./DomainModal";

interface Props {
  roomId: number;
}

/** 편집 모드 툴바 — 테이블 추가, Undo/Redo, 변경 이력. 모든 저장은 현재 방에만 적용된다. */
export function Toolbar({ roomId }: Props) {
  const { doc, historyOpen } = useStore();
  const dispatch = useDispatch();
  const [ddlOpen, setDdlOpen] = useState(false);
  const [domainOpen, setDomainOpen] = useState(false);
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
    // 도메인 키가 비어 있어도 서버가 방의 도메인으로 보정하므로 빈 문자열을 그대로 보낸다.
    const domain = doc.domains.stat ? "stat" : (Object.keys(doc.domains)[0] ?? "");
    const op: Op = { type: "table.add", user, payload: { name, domain, desc: "새 테이블" } };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
    dispatch({ type: "select", name });
  };

  const addMemo = () => {
    const memos = docMemos(doc);
    let id = "";
    do {
      id = `m${Math.random().toString(36).slice(2, 9)}`;
    } while (memos.some((m) => rowStr(m, 0) === id));
    // 자동 배치 영역(y≥30)을 피해 캔버스 왼쪽 위 빈 공간에 계단식으로 놓는다.
    const n = memos.length % 6;
    const op: Op = {
      type: "memo.add",
      user,
      payload: { id, text: "새 메모", x: 40 + n * 28, y: -70 + n * 28, color: MEMO_DEFAULT_COLOR },
    };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
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
      putSchema(roomId, newDoc, user)
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
      <button onClick={addMemo}>🗒 메모 추가</button>
      <button onClick={() => setDomainOpen(true)}>🎨 도메인 관리</button>
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
      <button onClick={() => exportSql(doc, "mysql")}>⬇ SQL·MySQL</button>
      <button onClick={() => exportSql(doc, "postgres")}>⬇ SQL·PG</button>
      <button onClick={() => exportDbml(doc)}>⬇ DBML</button>
      <button onClick={() => exportPng(toastErr)}>🖼 PNG</button>
      <button onClick={() => exportSvg(toastErr)}>🖼 SVG</button>
      <button onClick={() => exportPdf(toastErr)}>🖨 PDF</button>
      <span className="tbnote">모든 편집은 즉시 전체 접속자에게 반영되고 DB에 저장됩니다</span>
      {ddlOpen && <DdlImportModal roomId={roomId} onClose={() => setDdlOpen(false)} />}
      {domainOpen && <DomainModal onClose={() => setDomainOpen(false)} />}
    </div>
  );
}
