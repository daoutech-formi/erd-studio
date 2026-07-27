// 사용자 식별 — 이름은 localStorage에 저장하고, 색상은 이름 해시로 결정한다.

export interface UserInfo {
  name: string;
  color: string;
}

const STORAGE_KEY = "erd_user";

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
