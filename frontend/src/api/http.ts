import type { HistoryDiff, HistoryEntry, SchemaDoc } from "../types";

async function parse<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as { error?: string };
  if (!res.ok) {
    throw new Error(body.error ?? `HTTP ${res.status}`);
  }
  return body as T;
}

const userHeader = (user: string): Record<string, string> => ({ "X-User": encodeURIComponent(user) });

export interface RoomInfo {
  id: number;
  name: string;
  createdBy: string;
  createdAt: string;
  tableCount: number;
  userCount: number;
}

export function fetchRooms(): Promise<RoomInfo[]> {
  return fetch("/api/rooms").then((res) => parse<RoomInfo[]>(res));
}

export function createRoom(name: string, user: string, clientKey: string): Promise<RoomInfo> {
  return fetch("/api/rooms", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...userHeader(user) },
    body: JSON.stringify({ name, clientKey }),
  }).then((res) => parse<RoomInfo>(res));
}

/** 이름 변경 시 같은 브라우저(clientKey)로 만든 방들의 생성자 표시명을 갱신한다. */
export function renameRoomCreator(clientKey: string, name: string): Promise<{ ok: boolean; updated: number }> {
  return fetch("/api/rooms/creator-name", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ clientKey, name }),
  }).then((res) => parse(res));
}

export function deleteRoom(roomId: number): Promise<{ ok: boolean }> {
  return fetch(`/api/rooms/${roomId}`, { method: "DELETE" }).then((res) => parse(res));
}

export function fetchSchema(roomId: number): Promise<SchemaDoc> {
  return fetch(`/api/rooms/${roomId}/schema`).then((res) => parse<SchemaDoc>(res));
}

export function putSchema(
  roomId: number,
  doc: SchemaDoc,
  user: string,
): Promise<{ ok: boolean; tables: number; relations: number }> {
  return fetch(`/api/rooms/${roomId}/schema`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...userHeader(user) },
    body: JSON.stringify(doc),
  }).then((res) => parse(res));
}

export interface DdlImportSummary {
  ok: boolean;
  tables: number;
  relations: number;
  added: string[];
  updated: string[];
  removed: string[];
  unchanged: number;
  newRelations: number;
  newDomains: string[];
}

export function previewDdl(roomId: number, ddl: string, mode: "merge" | "replace"): Promise<DdlImportSummary> {
  return fetch(`/api/rooms/${roomId}/ddl/preview`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ddl, mode }),
  }).then((res) => parse(res));
}

export function importDdl(
  roomId: number,
  ddl: string,
  mode: "merge" | "replace",
  user: string,
): Promise<DdlImportSummary> {
  return fetch(`/api/rooms/${roomId}/ddl/import`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...userHeader(user) },
    body: JSON.stringify({ ddl, mode }),
  }).then((res) => parse(res));
}

export function fetchHistory(roomId: number, limit = 50): Promise<HistoryEntry[]> {
  return fetch(`/api/rooms/${roomId}/history?limit=${limit}`).then((res) => parse<HistoryEntry[]>(res));
}

export function fetchHistoryDiff(roomId: number, id: number): Promise<HistoryDiff> {
  return fetch(`/api/rooms/${roomId}/history/${id}/diff`).then((res) => parse<HistoryDiff>(res));
}

export function restoreHistory(roomId: number, id: number, user: string): Promise<{ ok: boolean }> {
  return fetch(`/api/rooms/${roomId}/history/${id}/restore`, {
    method: "POST",
    headers: userHeader(user),
  }).then((res) => parse(res));
}
