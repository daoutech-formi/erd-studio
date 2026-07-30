import type { HistoryEntry, SchemaDoc } from "../types";

async function parse<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as { error?: string };
  if (!res.ok) {
    throw new Error(body.error ?? `HTTP ${res.status}`);
  }
  return body as T;
}

export function fetchSchema(): Promise<SchemaDoc> {
  return fetch("/api/schema").then((res) => parse<SchemaDoc>(res));
}

export function putSchema(doc: SchemaDoc, user: string): Promise<{ ok: boolean; tables: number; relations: number }> {
  return fetch("/api/schema", {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-User": encodeURIComponent(user) },
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

export function previewDdl(ddl: string, mode: "merge" | "replace"): Promise<DdlImportSummary> {
  return fetch("/api/ddl/preview", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ddl, mode }),
  }).then((res) => parse(res));
}

export function importDdl(ddl: string, mode: "merge" | "replace", user: string): Promise<DdlImportSummary> {
  return fetch("/api/ddl/import", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-User": encodeURIComponent(user) },
    body: JSON.stringify({ ddl, mode }),
  }).then((res) => parse(res));
}

export function fetchHistory(limit = 50): Promise<HistoryEntry[]> {
  return fetch(`/api/history?limit=${limit}`).then((res) => parse<HistoryEntry[]>(res));
}

export function restoreHistory(id: number, user: string): Promise<{ ok: boolean }> {
  return fetch(`/api/history/${id}/restore`, {
    method: "POST",
    headers: { "X-User": encodeURIComponent(user) },
  }).then((res) => parse(res));
}
