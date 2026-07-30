// 공용 타입 — WebSocket/HTTP 프로토콜을 바꿀 때는 백엔드 ws/dto·web/dto와 함께 수정한다.

export interface DomainDef {
  name: string;
  color: string;
}

/**
 * 스키마 문서의 배열 행. 서버와 동일한 포맷을 사용한다.
 * tables:    [name, domainKey, description, hub?, posX?, posY?]
 * relations: [childName, parentName, label?]
 * columns:   [name, colType, comment, flag?]
 */
export type Row = (string | number | boolean | null)[];

export interface SchemaDoc {
  domains: Record<string, DomainDef>;
  tables: Row[];
  relations: Row[];
  columns: Record<string, Row[]>;
}

export const rowStr = (row: Row, i: number): string => {
  const v = row[i];
  return v === null || v === undefined ? "" : String(v);
};

export const rowBool = (row: Row, i: number): boolean => row[i] === true;

export const rowNum = (row: Row, i: number): number | null =>
  typeof row[i] === "number" ? (row[i] as number) : null;

export type OpType =
  | "table.add"
  | "table.apply"
  | "table.delete"
  | "table.move"
  | "domain.apply"
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
