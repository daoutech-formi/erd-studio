import { useEffect, useState } from "react";
import { erdSocket } from "../api/socket";
import { useDispatch, useStore } from "../state/schemaStore";
import { invertOp } from "../state/undo";
import type { Op, Row, SchemaDoc } from "../types";
import { rowBool, rowStr } from "../types";

const FLAGS = ["", "PK", "UK", "FK", "UK/FK"];

interface ColDraft {
  name: string;
  type: string;
  comment: string;
  flag: string;
}

interface RelDraft {
  target: string;
  label: string;
  cardinality: string;
}

/** 카디널리티 선택지 — 값이 비면 기본(N:1)으로 저장돼 문서가 가볍게 유지된다. */
const CARDINALITIES: Array<[string, string]> = [
  ["", "N:1 (기본)"],
  ["1:1", "1:1"],
  ["N:M", "N:M"],
];

function initCols(doc: SchemaDoc, table: string): ColDraft[] {
  return (doc.columns[table] ?? []).map((c) => ({
    name: rowStr(c, 0), type: rowStr(c, 1), comment: rowStr(c, 2), flag: rowStr(c, 3),
  }));
}

function initRels(doc: SchemaDoc, table: string): RelDraft[] {
  return doc.relations
    .filter((r) => rowStr(r, 0) === table)
    .map((r) => ({ target: rowStr(r, 1), label: rowStr(r, 2), cardinality: rowStr(r, 3) }));
}

/** 편집 모드에서 락을 잡은 테이블의 이름/도메인/설명/컬럼/관계를 고친다. */
export function EditForm({ table }: { table: string }) {
  const { doc } = useStore();
  const dispatch = useDispatch();
  const row = doc?.tables.find((t) => rowStr(t, 0) === table);
  const [name, setName] = useState(table);
  const [domain, setDomain] = useState(row ? rowStr(row, 1) : "");
  const [desc, setDesc] = useState(row ? rowStr(row, 2) : "");
  const [cols, setCols] = useState<ColDraft[]>(() => (doc ? initCols(doc, table) : []));
  const [rels, setRels] = useState<RelDraft[]>(() => (doc ? initRels(doc, table) : []));
  /** 방금 추가한 테이블은 서버 왕복 전이라 첫 렌더에 row가 없다 — 도착하면 그때 한 번 초기화한다. */
  const [seeded, setSeeded] = useState(Boolean(row));

  useEffect(() => {
    if (seeded || !doc || !row) {
      return;
    }
    setName(rowStr(row, 0));
    setDomain(rowStr(row, 1));
    setDesc(rowStr(row, 2));
    setCols(initCols(doc, table));
    setRels(initRels(doc, table));
    setSeeded(true);
  }, [seeded, doc, row, table]);

  if (!doc || !row) {
    return null;
  }
  const otherTables = doc.tables.map((t) => rowStr(t, 0)).filter((t) => t !== table).sort();
  const user = erdSocket.currentUser();

  const close = () => {
    erdSocket.sendLock("release", table);
    dispatch({ type: "editing", name: null });
    dispatch({ type: "select", name: null });
  };

  const apply = () => {
    const newName = name.trim();
    if (!newName) {
      dispatch({ type: "toast", toast: { message: "테이블명을 입력하세요.", kind: "err" } });
      return;
    }
    if (newName !== table && doc.tables.some((t) => rowStr(t, 0) === newName)) {
      dispatch({ type: "toast", toast: { message: "이미 존재하는 테이블명입니다.", kind: "err" } });
      return;
    }
    const op: Op = {
      type: "table.apply",
      user,
      payload: {
        oldName: table,
        table: [newName, domain, desc.trim(), rowBool(row, 3)] as Row,
        columns: cols.filter((c) => c.name.trim())
          .map((c) => [c.name.trim(), c.type.trim() || "varchar(50)", c.comment.trim(), c.flag] as Row),
        relations: rels.map((r) => [newName, r.target, r.label.trim(), r.cardinality] as Row),
      },
    };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
    if (newName !== table) {
      erdSocket.sendLock("release", table);
      erdSocket.sendLock("acquire", newName);
      dispatch({ type: "select", name: newName });
      dispatch({ type: "editing", name: newName });
    }
    dispatch({ type: "toast", toast: { message: "적용됨 — 자동 저장되었습니다.", kind: "ok" } });
  };

  const remove = () => {
    if (!window.confirm(`'${table}' 테이블을 삭제할까요? (관련 관계도 함께 삭제)`)) {
      return;
    }
    const op: Op = { type: "table.delete", user, payload: { name: table } };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
    close();
  };

  const setCol = (i: number, patch: Partial<ColDraft>) =>
    setCols(cols.map((c, j) => (j === i ? { ...c, ...patch } : c)));
  const setRel = (i: number, patch: Partial<RelDraft>) =>
    setRels(rels.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  return (
    <div className="panel">
      <div className="panel-head">
        <h3>테이블 편집</h3>
        <button className="mini danger" onClick={remove}>테이블 삭제</button>
      </div>
      <label className="fl">테이블명</label>
      <input className="fi" value={name} onChange={(e) => setName(e.target.value)} />
      <label className="fl">도메인</label>
      <select className="fi" value={domain} onChange={(e) => setDomain(e.target.value)}>
        {Object.entries(doc.domains).map(([k, d]) => <option key={k} value={k}>{d.name}</option>)}
      </select>
      <label className="fl">설명</label>
      <input className="fi" value={desc} onChange={(e) => setDesc(e.target.value)} />

      <div className="sect">
        컬럼 <button className="mini" onClick={() => setCols([...cols, { name: "new_col", type: "varchar(50)", comment: "", flag: "" }])}>+ 추가</button>
      </div>
      {cols.map((c, i) => (
        <div className="crow" key={i}>
          <input className="ci cn2" value={c.name} placeholder="컬럼명" onChange={(e) => setCol(i, { name: e.target.value })} />
          <input className="ci ct2" value={c.type} placeholder="타입" onChange={(e) => setCol(i, { type: e.target.value })} />
          <input className="ci cc2" value={c.comment} placeholder="설명" onChange={(e) => setCol(i, { comment: e.target.value })} />
          <select className="ci cf2" value={c.flag} onChange={(e) => setCol(i, { flag: e.target.value })}>
            {FLAGS.map((f) => <option key={f} value={f}>{f}</option>)}
          </select>
          <button className="mini danger" onClick={() => setCols(cols.filter((_, j) => j !== i))}>×</button>
        </div>
      ))}

      <div className="sect">
        참조 관계 (이 테이블 → 대상) <button className="mini" onClick={() => setRels([...rels, { target: otherTables[0] ?? table, label: "", cardinality: "" }])}>+ 추가</button>
      </div>
      {rels.length === 0 && <div className="hint">없음</div>}
      {rels.map((r, i) => (
        <div className="rrow" key={i}>
          <span style={{ color: "#7b8399" }}>→</span>
          <select className="ci rt2" value={r.target} onChange={(e) => setRel(i, { target: e.target.value })}>
            {otherTables.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <select className="ci rc2" value={r.cardinality} onChange={(e) => setRel(i, { cardinality: e.target.value })}>
            {CARDINALITIES.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
          </select>
          <input className="ci rl2" value={r.label} placeholder="라벨(선택)" onChange={(e) => setRel(i, { label: e.target.value })} />
          <button className="mini danger" onClick={() => setRels(rels.filter((_, j) => j !== i))}>×</button>
        </div>
      ))}

      <div style={{ marginTop: 12, display: "flex", gap: 6 }}>
        <button className="mini primary" onClick={apply}>적용</button>
        <button className="mini" onClick={close}>닫기</button>
      </div>
      <div className="hint">적용 즉시 모든 접속자에게 반영되고 DB에 자동 저장됩니다. Ctrl+Z로 되돌릴 수 있습니다.</div>
    </div>
  );
}
