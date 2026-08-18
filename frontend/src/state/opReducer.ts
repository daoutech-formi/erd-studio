import type { DomainDef, Op, Row, SchemaDoc } from "../types";
import { MEMO_DEFAULT_COLOR, docMemos, rowBool, rowNum, rowStr, rowStrArr } from "../types";

// 서버가 에코한 op를 로컬 문서에 반영하는 순수 함수.
// 발신자/수신자 구분 없이 모든 클라이언트가 동일한 경로로 상태를 갱신한다.

interface TableAddPayload {
  name: string;
  domain: string;
  desc?: string;
}

interface TableApplyPayload {
  oldName: string;
  table: Row;
  columns: Row[];
  relations: Row[];
}

export function applyOpToDoc(doc: SchemaDoc, op: Op): SchemaDoc {
  switch (op.type) {
    case "table.add":
      return applyAdd(doc, op.payload as unknown as TableAddPayload);
    case "table.apply":
      return applyTableApply(doc, op.payload as unknown as TableApplyPayload);
    case "table.delete":
      return applyDelete(doc, String(op.payload.name ?? ""));
    case "table.move":
      return applyMove(doc, op.payload as { name?: string; x?: number; y?: number });
    case "domain.apply":
      return applyDomains(doc, (op.payload.domains as Row[]) ?? []);
    case "memo.add":
      return applyMemoAdd(doc, op.payload as MemoAddPayload);
    case "memo.apply":
      return applyMemoApply(doc, op.payload as MemoApplyPayload);
    case "memo.delete":
      return { ...doc, memos: docMemos(doc).filter((m) => rowStr(m, 0) !== String(op.payload.id ?? "")) };
    case "memo.move":
      return applyMemoMove(doc, op.payload as { id?: string; x?: number; y?: number });
    case "schema.replace":
      return op.payload.doc as SchemaDoc;
  }
}

interface MemoAddPayload {
  id?: string;
  text?: string;
  x?: number;
  y?: number;
  color?: string;
  links?: string[];
}

interface MemoApplyPayload {
  id?: string;
  text?: string;
  color?: string;
  links?: string[];
}

function applyMemoAdd(doc: SchemaDoc, p: MemoAddPayload): SchemaDoc {
  if (!p.id || docMemos(doc).some((m) => rowStr(m, 0) === p.id)) {
    return doc;
  }
  return {
    ...doc,
    memos: [...docMemos(doc), [p.id, p.text ?? "", p.x ?? 0, p.y ?? 0, p.color ?? MEMO_DEFAULT_COLOR, p.links ?? []]],
  };
}

function applyMemoApply(doc: SchemaDoc, p: MemoApplyPayload): SchemaDoc {
  const memos = docMemos(doc).map((m) =>
    rowStr(m, 0) === p.id
      ? [rowStr(m, 0), p.text ?? "", rowNum(m, 2), rowNum(m, 3), p.color ?? rowStr(m, 4), p.links ?? rowStrArr(m, 5)]
      : m,
  );
  return { ...doc, memos };
}

function applyMemoMove(doc: SchemaDoc, p: { id?: string; x?: number; y?: number }): SchemaDoc {
  if (typeof p.x !== "number" || typeof p.y !== "number") {
    return doc;
  }
  const memos = docMemos(doc).map((m) =>
    rowStr(m, 0) === p.id ? [rowStr(m, 0), rowStr(m, 1), p.x ?? 0, p.y ?? 0, rowStr(m, 4), rowStrArr(m, 5)] : m,
  );
  return { ...doc, memos };
}

/** 테이블 이름 변경/삭제에 맞춰 메모 links를 정리한다. rename이 null이면 제거만 한다. */
function remapMemoLinks(doc: SchemaDoc, from: string, to: string | null): Row[] {
  return docMemos(doc).map((m) => {
    const links = rowStrArr(m, 5);
    if (!links.includes(from)) {
      return m;
    }
    const next = to === null ? links.filter((n) => n !== from) : links.map((n) => (n === from ? to : n));
    return [rowStr(m, 0), rowStr(m, 1), rowNum(m, 2), rowNum(m, 3), rowStr(m, 4), next];
  });
}

/** 도메인 목록 교체 — 사라진 도메인에 속한 테이블은 첫 도메인으로 옮긴다(서버와 동일 규칙). */
function applyDomains(doc: SchemaDoc, rows: Row[]): SchemaDoc {
  if (rows.length === 0) {
    return doc;
  }
  const domains: Record<string, DomainDef> = {};
  for (const r of rows) {
    domains[rowStr(r, 0)] = { name: rowStr(r, 1), color: rowStr(r, 2) };
  }
  const fallback = rowStr(rows[0], 0);
  const tables = doc.tables.map((t) =>
    domains[rowStr(t, 1)]
      ? t
      : [rowStr(t, 0), fallback, rowStr(t, 2), rowBool(t, 3), rowNum(t, 4), rowNum(t, 5)],
  );
  return { ...doc, domains, tables };
}

function applyAdd(doc: SchemaDoc, p: TableAddPayload): SchemaDoc {
  if (doc.tables.some((t) => rowStr(t, 0) === p.name)) {
    return doc;
  }
  return { ...doc, tables: [...doc.tables, [p.name, p.domain, p.desc ?? "", false, null, null]] };
}

function applyTableApply(doc: SchemaDoc, p: TableApplyPayload): SchemaDoc {
  const newName = rowStr(p.table, 0);
  const tables = doc.tables.map((t) => {
    if (rowStr(t, 0) !== p.oldName) {
      return t;
    }
    // 위치(4,5)는 편집 폼이 다루지 않으므로 기존 값을 유지한다.
    return [newName, rowStr(p.table, 1), rowStr(p.table, 2), rowBool(p.table, 3), rowNum(t, 4), rowNum(t, 5)];
  });

  const columns = { ...doc.columns };
  delete columns[p.oldName];
  columns[newName] = p.columns;

  let relations = doc.relations.filter((r) => rowStr(r, 0) !== p.oldName);
  if (newName !== p.oldName) {
    relations = relations.map((r) =>
      rowStr(r, 1) === p.oldName ? [rowStr(r, 0), newName, rowStr(r, 2), rowStr(r, 3)] : r,
    );
  }
  relations = [...relations, ...p.relations.map((r) => [newName, rowStr(r, 1), rowStr(r, 2), rowStr(r, 3)] as Row)];
  const memos = newName !== p.oldName ? remapMemoLinks(doc, p.oldName, newName) : doc.memos;
  return { ...doc, tables, columns, relations, memos };
}

function applyDelete(doc: SchemaDoc, name: string): SchemaDoc {
  const columns = { ...doc.columns };
  delete columns[name];
  return {
    ...doc,
    tables: doc.tables.filter((t) => rowStr(t, 0) !== name),
    relations: doc.relations.filter((r) => rowStr(r, 0) !== name && rowStr(r, 1) !== name),
    columns,
    memos: remapMemoLinks(doc, name, null),
  };
}

function applyMove(doc: SchemaDoc, p: { name?: string; x?: number; y?: number }): SchemaDoc {
  if (typeof p.x !== "number" || typeof p.y !== "number") {
    return doc;
  }
  const tables = doc.tables.map((t) =>
    rowStr(t, 0) === p.name ? [rowStr(t, 0), rowStr(t, 1), rowStr(t, 2), rowBool(t, 3), p.x ?? null, p.y ?? null] : t,
  );
  return { ...doc, tables };
}
