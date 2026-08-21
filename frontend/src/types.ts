// 공용 타입 — WebSocket/HTTP 프로토콜을 바꿀 때는 백엔드 ws/dto·web/dto와 함께 수정한다.

export interface DomainDef {
  name: string;
  color: string;
}

/**
 * 스키마 문서의 배열 행. 서버와 동일한 포맷을 사용한다.
 * tables:    [name, domainKey, description, hub?, posX?, posY?]
 * relations: [childName, parentName, label?, cardinality?] (cardinality: N:1(기본)/1:1/N:M)
 * columns:   [name, colType, comment, flag?]
 * memos:     [id, text, x, y, color, links?, w?, h?] (links: 연결된 테이블명 배열 / w·h: 사용자 지정 크기, 없으면 기본 크기)
 */
export type Row = (string | number | boolean | null | string[])[];

export interface SchemaDoc {
  domains: Record<string, DomainDef>;
  tables: Row[];
  relations: Row[];
  columns: Record<string, Row[]>;
  /** 캔버스에 붙이는 스티키 메모. 메모 도입 전 문서에는 없을 수 있다. */
  memos?: Row[];
}

/** 메모 배경색 팔레트 — 첫 값이 기본색. 어두운 글자가 읽히는 파스텔 톤만 쓴다. */
export const MEMO_COLORS = ["#ffd479", "#7fd3a8", "#8ab0d0", "#f2a0c4"] as const;
export const MEMO_DEFAULT_COLOR: string = MEMO_COLORS[0];

export const docMemos = (doc: SchemaDoc): Row[] => doc.memos ?? [];

/** 메모 하나에 연결할 수 있는 테이블 최대 개수 — 서버(OpService.MAX_MEMO_LINKS)와 동일해야 한다. */
export const MAX_MEMO_LINKS = 10;

export const rowStr = (row: Row, i: number): string => {
  const v = row[i];
  return v === null || v === undefined ? "" : String(v);
};

export const rowBool = (row: Row, i: number): boolean => row[i] === true;

export const rowNum = (row: Row, i: number): number | null =>
  typeof row[i] === "number" ? (row[i] as number) : null;

/** 행의 i번째가 배열이면 문자열 배열로 돌려준다 — 메모 links(인덱스 5) 접근용. */
export const rowStrArr = (row: Row, i: number): string[] =>
  Array.isArray(row[i]) ? (row[i] as unknown[]).map(String) : [];

export type OpType =
  | "table.add"
  | "table.apply"
  | "table.delete"
  | "table.move"
  | "domain.apply"
  | "memo.add"
  | "memo.apply"
  | "memo.delete"
  | "memo.move"
  | "memo.resize"
  | "schema.replace";

export interface Op {
  type: OpType;
  user: string;
  payload: Record<string, unknown>;
}

/** id는 WebSocket 세션(탭) 단위, clientKey는 브라우저 단위 식별자. 접속자 목록은 clientKey로 중복 제거된다. */
export interface ClientInfo {
  id: string;
  clientKey: string;
  user: string;
  color: string;
}

export interface HistoryEntry {
  id: number;
  userName: string;
  opKind: string;
  target: string;
  createdAt: string;
}

/** 서버 → 클라이언트 WebSocket 메시지. */
export type WsIncoming =
  | { kind: "presence"; users: ClientInfo[] }
  | { kind: "op"; op: Op; seq: number }
  | { kind: "locks"; locks: Record<string, ClientInfo> }
  | { kind: "move"; table: string; x: number; y: number; id: string }
  | { kind: "error"; message: string; fatal?: boolean };
