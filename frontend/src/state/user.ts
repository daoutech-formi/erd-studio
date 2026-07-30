// 사용자 식별 — 이름은 localStorage에 저장하고, 색상은 이름 해시로 결정한다.
// clientKey는 브라우저 단위 식별자로, 같은 브라우저의 여러 탭을 서버에서 1명으로 집계하는 데 쓴다.

export interface UserInfo {
  name: string;
  color: string;
}

const STORAGE_KEY = "erd_user";
const CLIENT_KEY_STORAGE_KEY = "erd_client_key";

const PALETTE = [
  "#4f8cff", "#37c98b", "#ff7a7a", "#ffb454", "#c17aff", "#00c2d1",
  "#e86ab0", "#8ab0d0", "#f2d94e", "#7ee0a3", "#ff8f5e", "#9aa5ff",
];

export function colorFor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  }
  return PALETTE[hash % PALETTE.length];
}

export function loadUser(): UserInfo | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as UserInfo;
    if (typeof parsed.name !== "string" || parsed.name.length < 1 || parsed.name.length > 20) {
      return null;
    }
    return { name: parsed.name, color: colorFor(parsed.name) };
  } catch {
    return null;
  }
}

export function saveUser(name: string): UserInfo {
  const info = { name, color: colorFor(name) };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(info));
  return info;
}

function newClientKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `ck-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 브라우저에 한 번 발급해 계속 재사용하는 식별자 (탭을 여러 개 열어도 같은 값). */
function loadClientKey(): string {
  try {
    const saved = localStorage.getItem(CLIENT_KEY_STORAGE_KEY);
    if (saved && saved.length > 0) {
      return saved;
    }
    const key = newClientKey();
    localStorage.setItem(CLIENT_KEY_STORAGE_KEY, key);
    return key;
  } catch {
    return newClientKey();
  }
}

export const clientKey: string = loadClientKey();
