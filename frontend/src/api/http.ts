import type { HistoryDiff, HistoryEntry, SchemaDoc } from "../types";

async function parse<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as { error?: string };
  if (!res.ok) {
    throw new Error(body.error ?? `HTTP ${res.status}`);
  }
  return body as T;
}

const userHeader = (user: string): Record<string, string> => ({ "X-User": encodeURIComponent(user) });

// --- 현재 프로젝트 (모든 요청에 X-Project-Id 헤더로 전파) ---
const PROJECT_KEY = "erd_project";
let currentProject = localStorage.getItem(PROJECT_KEY) ?? "";

export function getProject(): string {
  return currentProject;
}

export function setProject(slug: string): void {
  currentProject = slug;
  if (slug) {
    localStorage.setItem(PROJECT_KEY, slug);
  } else {
    localStorage.removeItem(PROJECT_KEY);
  }
}

/** 선택된 프로젝트 slug 를 X-Project-Id 로 싣는 fetch 래퍼. 서버는 미지 slug 면 legacy 로 폴백한다. */
function afetch(input: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (currentProject) {
    headers.set("X-Project-Id", currentProject);
  }
  return fetch(input, { ...init, headers });
}

/** 프로젝트 멤버십 역할. SSO 미사용·비멤버는 null. */
export type ProjectRole = "ADMIN" | "EDITOR" | "VIEWER";

/** 프로젝트 — ERD 방의 묶음. */
export interface Project {
  id: number;
  slug: string;
  name: string;
  roomCount: number;
  /** 내 역할 — superAdmin 은 ADMIN 으로 내려온다. */
  myRole: ProjectRole | null;
}

export function fetchProjects(): Promise<Project[]> {
  return afetch("/api/projects").then((res) => parse<Project[]>(res));
}

export function createProject(name: string): Promise<Project> {
  return afetch("/api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name }),
  }).then((res) => parse<Project>(res));
}

export function deleteProject(slug: string): Promise<{ ok: boolean }> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}`, { method: "DELETE" }).then((res) => parse(res));
}

// --- 프로젝트 멤버 관리 (프로젝트 ADMIN 전용) ---

export interface ProjectMemberInfo {
  userId: number;
  username: string | null;
  displayName: string | null;
  role: ProjectRole;
}

export interface AssignableUser {
  id: number;
  username: string;
  displayName: string;
}

export function fetchMembers(slug: string): Promise<ProjectMemberInfo[]> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}/members`).then((res) => parse<ProjectMemberInfo[]>(res));
}

/** 멤버 목록을 통째로 교체한다. 새 목록에 관리자가 1명 이상 있어야 한다. */
export function replaceMembers(
  slug: string,
  members: { userId: number; role: ProjectRole }[],
): Promise<{ ok: boolean }> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}/members`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ members }),
  }).then((res) => parse(res));
}

export function fetchAssignableUsers(slug: string): Promise<AssignableUser[]> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}/assignable-users`)
    .then((res) => parse<AssignableUser[]>(res));
}

// --- 초대 링크 (프로젝트 ADMIN 이 생성, 로그인 사용자가 수락) ---

export interface InviteInfo {
  id: number;
  token: string;
  role: ProjectRole;
  /** null = 무기한. */
  expiresAt: string | null;
  /** 0 = 무제한. */
  maxUses: number;
  usedCount: number;
  /** 만료됐거나 사용횟수를 소진해 더 못 쓰는 링크. */
  exhausted: boolean;
}

export interface InvitePreview {
  projectName: string | null;
  role: ProjectRole | null;
  valid: boolean;
  reason: string | null;
  alreadyMember: boolean;
}

export interface InviteAcceptResult {
  ok: boolean;
  projectSlug: string;
  projectName: string;
  role: ProjectRole;
  alreadyMember: boolean;
}

export function fetchInvites(slug: string): Promise<InviteInfo[]> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}/invites`).then((res) => parse<InviteInfo[]>(res));
}

export function createInvite(
  slug: string,
  role: ProjectRole,
  expiresInDays: number | null,
  maxUses: number,
): Promise<InviteInfo> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}/invites`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ role, expiresInDays, maxUses }),
  }).then((res) => parse<InviteInfo>(res));
}

export function revokeInvite(slug: string, inviteId: number): Promise<{ ok: boolean }> {
  return afetch(`/api/projects/${encodeURIComponent(slug)}/invites/${inviteId}`, { method: "DELETE" })
    .then((res) => parse(res));
}

export function fetchInvitePreview(token: string): Promise<InvitePreview> {
  return afetch(`/api/invites/${encodeURIComponent(token)}`).then((res) => parse<InvitePreview>(res));
}

export function acceptInvite(token: string): Promise<InviteAcceptResult> {
  return afetch(`/api/invites/${encodeURIComponent(token)}/accept`, { method: "POST" })
    .then((res) => parse<InviteAcceptResult>(res));
}

/** 현재 로그인 상태. oidcEnabled 가 false 면 로그인 버튼 자체를 숨긴다. */
export interface Me {
  authenticated: boolean;
  userId: number | null;
  username: string | null;
  displayName: string | null;
  superAdmin: boolean;
  oidcEnabled: boolean;
}

export function fetchMe(): Promise<Me> {
  return afetch("/api/auth/me").then((res) => parse<Me>(res));
}

export function logout(): Promise<void> {
  return afetch("/api/auth/logout", { method: "POST" }).then((res) => {
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
  });
}

export interface RoomInfo {
  id: number;
  name: string;
  createdBy: string;
  createdAt: string;
  tableCount: number;
  userCount: number;
  /** 소속 프로젝트 — 딥링크가 프로젝트를 넘나들 때 선택 전환용. */
  projectSlug: string;
}

export function fetchRooms(): Promise<RoomInfo[]> {
  return afetch("/api/rooms").then((res) => parse<RoomInfo[]>(res));
}

/** 딥링크(#/room/:id) 진입용 단건 조회 — 현재 프로젝트와 무관하게 찾는다. */
export function fetchRoom(roomId: number): Promise<RoomInfo> {
  return afetch(`/api/rooms/${roomId}`).then((res) => parse<RoomInfo>(res));
}

export function createRoom(name: string, user: string, clientKey: string): Promise<RoomInfo> {
  return afetch("/api/rooms", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...userHeader(user) },
    body: JSON.stringify({ name, clientKey }),
  }).then((res) => parse<RoomInfo>(res));
}

/** 이름 변경 시 같은 브라우저(clientKey)로 만든 방들의 생성자 표시명을 갱신한다. */
export function renameRoomCreator(clientKey: string, name: string): Promise<{ ok: boolean; updated: number }> {
  return afetch("/api/rooms/creator-name", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ clientKey, name }),
  }).then((res) => parse(res));
}

export function deleteRoom(roomId: number): Promise<{ ok: boolean }> {
  return afetch(`/api/rooms/${roomId}`, { method: "DELETE" }).then((res) => parse(res));
}

export function fetchSchema(roomId: number): Promise<SchemaDoc> {
  return afetch(`/api/rooms/${roomId}/schema`).then((res) => parse<SchemaDoc>(res));
}

export function putSchema(
  roomId: number,
  doc: SchemaDoc,
  user: string,
): Promise<{ ok: boolean; tables: number; relations: number }> {
  return afetch(`/api/rooms/${roomId}/schema`, {
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
  return afetch(`/api/rooms/${roomId}/ddl/preview`, {
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
  return afetch(`/api/rooms/${roomId}/ddl/import`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...userHeader(user) },
    body: JSON.stringify({ ddl, mode }),
  }).then((res) => parse(res));
}

/** Smart Query 임포트 — 추출 쿼리 결과 JSON을 미리보기/적용한다. 응답은 DDL 임포트와 동일. */
export function previewSmart(roomId: number, json: string, mode: "merge" | "replace"): Promise<DdlImportSummary> {
  return afetch(`/api/rooms/${roomId}/smart/preview`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ json, mode }),
  }).then((res) => parse(res));
}

export function importSmart(
  roomId: number,
  json: string,
  mode: "merge" | "replace",
  user: string,
): Promise<DdlImportSummary> {
  return afetch(`/api/rooms/${roomId}/smart/import`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...userHeader(user) },
    body: JSON.stringify({ json, mode }),
  }).then((res) => parse(res));
}

export function fetchHistory(roomId: number, limit = 50): Promise<HistoryEntry[]> {
  return afetch(`/api/rooms/${roomId}/history?limit=${limit}`).then((res) => parse<HistoryEntry[]>(res));
}

export function fetchHistoryDiff(roomId: number, id: number): Promise<HistoryDiff> {
  return afetch(`/api/rooms/${roomId}/history/${id}/diff`).then((res) => parse<HistoryDiff>(res));
}

export function restoreHistory(roomId: number, id: number, user: string): Promise<{ ok: boolean }> {
  return afetch(`/api/rooms/${roomId}/history/${id}/restore`, {
    method: "POST",
    headers: userHeader(user),
  }).then((res) => parse(res));
}
