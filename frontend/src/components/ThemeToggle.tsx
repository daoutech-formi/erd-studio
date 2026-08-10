import { useState } from "react";
import { applyTheme, loadTheme, type Theme } from "../state/theme";

/** 다크/라이트 테마 토글 버튼. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(loadTheme);
  const toggle = () => {
    const next: Theme = theme === "dark" ? "light" : "dark";
    applyTheme(next);
    setTheme(next);
  };
  return (
    <button onClick={toggle} title="테마 전환">
      {theme === "dark" ? "☀ 라이트" : "🌙 다크"}
    </button>
  );
}
