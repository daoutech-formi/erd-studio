import { docMemos, rowBool, rowNum, rowStr, rowStrArr, type Row, type SchemaDoc } from "../types";

// 스냅샷 두 개(before → after)의 구조적 차이 계산 — 이력 diff 뷰어에서 사용한다.

export type ChangeKind = "added" | "removed" | "changed";

export interface FieldChange {
  label: string;
  from: string;
  to: string;
}

export interface ColumnDiff {
  name: string;
  kind: ChangeKind;
  /** added/removed일 때 "타입 · 코멘트" 요약, changed일 때는 changes 사용. */
  summary?: string;
  changes?: FieldChange[];
}

export interface TableDiff {
  name: string;
  kind: ChangeKind;
  /** 테이블 속성(도메인/설명/허브) 변경. */
  changes: FieldChange[];
  moved: boolean;
  columns: ColumnDiff[];
}

export interface ItemDiff {
  key: string;
  kind: ChangeKind;
  detail?: string;
}

export interface SchemaDiffResult {
  domains: ItemDiff[];
  tables: TableDiff[];
  relations: ItemDiff[];
  memos: ItemDiff[];
  empty: boolean;
}

const EMPTY_DOC: SchemaDoc = { domains: {}, tables: [], relations: [], columns: {}, memos: [] };

const byFirst = (rows: Row[]): Map<string, Row> => new Map(rows.map((r) => [rowStr(r, 0), r]));

const pushChange = (out: FieldChange[], label: string, from: string, to: string) => {
  if (from !== to) {
    out.push({ label, from, to });
  }
};

/** 컬럼 행 [name, colType, comment, flag?] 요약 표기. */
const colSummary = (row: Row): string => {
  const type = rowStr(row, 1);
  const comment = rowStr(row, 2);
  return comment ? `${type} · ${comment}` : type;
};

const diffColumns = (before: Row[], after: Row[]): ColumnDiff[] => {
  const prev = byFirst(before);
  const next = byFirst(after);
  const out: ColumnDiff[] = [];
  for (const [name, row] of next) {
    const old = prev.get(name);
    if (!old) {
      out.push({ name, kind: "added", summary: colSummary(row) });
      continue;
    }
    const changes: FieldChange[] = [];
    pushChange(changes, "타입", rowStr(old, 1), rowStr(row, 1));
    pushChange(changes, "코멘트", rowStr(old, 2), rowStr(row, 2));
    pushChange(changes, "구분", rowStr(old, 3), rowStr(row, 3));
    if (changes.length > 0) {
      out.push({ name, kind: "changed", changes });
    }
  }
  for (const [name, row] of prev) {
    if (!next.has(name)) {
      out.push({ name, kind: "removed", summary: colSummary(row) });
    }
  }
  return out;
};

const diffTables = (before: SchemaDoc, after: SchemaDoc): TableDiff[] => {
  const prev = byFirst(before.tables);
  const next = byFirst(after.tables);
  const out: TableDiff[] = [];
  for (const [name, row] of next) {
    const old = prev.get(name);
    if (!old) {
      out.push({ name, kind: "added", changes: [], moved: false, columns: [] });
      continue;
    }
    const changes: FieldChange[] = [];
    pushChange(changes, "도메인", rowStr(old, 1), rowStr(row, 1));
    pushChange(changes, "설명", rowStr(old, 2), rowStr(row, 2));
    pushChange(changes, "허브", rowBool(old, 3) ? "예" : "아니오", rowBool(row, 3) ? "예" : "아니오");
    const moved = rowNum(old, 4) !== rowNum(row, 4) || rowNum(old, 5) !== rowNum(row, 5);
    const columns = diffColumns(before.columns[name] ?? [], after.columns[name] ?? []);
    if (changes.length > 0 || moved || columns.length > 0) {
      out.push({ name, kind: "changed", changes, moved, columns });
    }
  }
  for (const name of prev.keys()) {
    if (!next.has(name)) {
      out.push({ name, kind: "removed", changes: [], moved: false, columns: [] });
    }
  }
  return out;
};

const diffDomains = (before: SchemaDoc, after: SchemaDoc): ItemDiff[] => {
  const out: ItemDiff[] = [];
  for (const [key, def] of Object.entries(after.domains)) {
    const old = before.domains[key];
    if (!old) {
      out.push({ key, kind: "added", detail: def.name });
    } else if (old.name !== def.name || old.color !== def.color) {
      const parts: string[] = [];
      if (old.name !== def.name) {
        parts.push(`이름 ${old.name} → ${def.name}`);
      }
      if (old.color !== def.color) {
        parts.push(`색 ${old.color} → ${def.color}`);
      }
      out.push({ key, kind: "changed", detail: parts.join(" · ") });
    }
  }
  for (const [key, def] of Object.entries(before.domains)) {
    if (!after.domains[key]) {
      out.push({ key, kind: "removed", detail: def.name });
    }
  }
  return out;
};

/** 관계 행 [child, parent, label?, cardinality?] — child→parent 쌍을 키로 삼는다. */
const relKey = (row: Row): string => `${rowStr(row, 0)} → ${rowStr(row, 1)}`;

const diffRelations = (before: SchemaDoc, after: SchemaDoc): ItemDiff[] => {
  const prev = new Map(before.relations.map((r) => [relKey(r), r]));
  const next = new Map(after.relations.map((r) => [relKey(r), r]));
  const out: ItemDiff[] = [];
  for (const [key, row] of next) {
    const old = prev.get(key);
    if (!old) {
      out.push({ key, kind: "added", detail: rowStr(row, 2) || undefined });
      continue;
    }
    const changes: FieldChange[] = [];
    pushChange(changes, "라벨", rowStr(old, 2), rowStr(row, 2));
    pushChange(changes, "관계", rowStr(old, 3), rowStr(row, 3));
    if (changes.length > 0) {
      out.push({ key, kind: "changed", detail: changes.map((c) => `${c.label} ${c.from || "(없음)"} → ${c.to || "(없음)"}`).join(" · ") });
    }
  }
  for (const key of prev.keys()) {
    if (!next.has(key)) {
      out.push({ key, kind: "removed" });
    }
  }
  return out;
};

/** 메모 텍스트 요약 — 첫 줄 앞부분만. */
const memoLabel = (row: Row): string => {
  const text = rowStr(row, 1).split("\n")[0];
  return text.length > 24 ? `${text.slice(0, 24)}…` : text || "(빈 메모)";
};

const diffMemos = (before: SchemaDoc, after: SchemaDoc): ItemDiff[] => {
  const prev = byFirst(docMemos(before));
  const next = byFirst(docMemos(after));
  const out: ItemDiff[] = [];
  for (const [id, row] of next) {
    const old = prev.get(id);
    if (!old) {
      out.push({ key: memoLabel(row), kind: "added" });
      continue;
    }
    const parts: string[] = [];
    if (rowStr(old, 1) !== rowStr(row, 1)) {
      parts.push("내용");
    }
    if (rowStr(old, 4) !== rowStr(row, 4)) {
      parts.push("색상");
    }
    if (rowNum(old, 2) !== rowNum(row, 2) || rowNum(old, 3) !== rowNum(row, 3)) {
      parts.push("위치");
    }
    if (rowNum(old, 6) !== rowNum(row, 6) || rowNum(old, 7) !== rowNum(row, 7)) {
      parts.push("크기");
    }
    if (rowStrArr(old, 5).join(",") !== rowStrArr(row, 5).join(",")) {
      parts.push("연결 테이블");
    }
    if (parts.length > 0) {
      out.push({ key: memoLabel(row), kind: "changed", detail: `${parts.join("·")} 변경` });
    }
  }
  for (const [id, row] of prev) {
    if (!next.has(id)) {
      out.push({ key: memoLabel(row), kind: "removed" });
    }
  }
  return out;
};

/** before가 null(방의 첫 이력)이면 빈 문서와 비교해 전부 추가로 계산된다. */
export function diffSchemaDocs(before: SchemaDoc | null, after: SchemaDoc): SchemaDiffResult {
  const base = before ?? EMPTY_DOC;
  const domains = diffDomains(base, after);
  const tables = diffTables(base, after);
  const relations = diffRelations(base, after);
  const memos = diffMemos(base, after);
  return {
    domains,
    tables,
    relations,
    memos,
    empty: domains.length === 0 && tables.length === 0 && relations.length === 0 && memos.length === 0,
  };
}
