import type { DomainDef, Op, Row, SchemaDoc } from "../types";
import { rowBool, rowNum, rowStr } from "../types";

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
    case "schema.replace":
      return op.payload.doc as SchemaDoc;
  }
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
      rowStr(r, 1) === p.oldName ? [rowStr(r, 0), newName, rowStr(r, 2)] : r,
    );
  }
  relations = [...relations, ...p.relations.map((r) => [newName, rowStr(r, 1), rowStr(r, 2)] as Row)];
  return { ...doc, tables, columns, relations };
}

function applyDelete(doc: SchemaDoc, name: string): SchemaDoc {
  const columns = { ...doc.columns };
  delete columns[name];
  return {
    ...doc,
    tables: doc.tables.filter((t) => rowStr(t, 0) !== name),
    relations: doc.relations.filter((r) => rowStr(r, 0) !== name && rowStr(r, 1) !== name),
    columns,
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
