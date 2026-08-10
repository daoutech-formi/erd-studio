// 테마 — <html data-theme="…"> 로 CSS 토큰을 전환하고 localStorage에 유지한다.

export type Theme = "dark" | "light";

const STORAGE_KEY = "erd_theme";

export function loadTheme(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // localStorage 접근 불가 — 이번 세션만 적용
  }
}
