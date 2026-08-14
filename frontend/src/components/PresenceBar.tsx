import { useEffect, useRef, useState } from "react";
import { useStore } from "../state/schemaStore";
import { clientKey } from "../state/user";

/** 접속자 배지 목록 — 클릭하면 접속 중인 사용자 전체 목록이 드롭다운으로 열린다. */
export function PresenceBar() {
  const { presence, connected } = useStore();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  // 바깥 클릭으로 닫기
  useEffect(() => {
    if (!open) {
      return;
    }
    const onDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  return (
    <div className="presence" ref={rootRef}>
      <button
        type="button"
        className="presence-btn"
        title={connected ? "실시간 연결됨 — 클릭해서 접속자 보기" : "연결 끊김 — 재접속 중"}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`conn-dot${connected ? " on" : ""}`} />
        {presence.map((p) => (
          <span key={p.clientKey} className="avatar" style={{ background: p.color }} title={p.user}>
            {p.user.slice(0, 1)}
          </span>
        ))}
        {presence.length > 0 && <span className="presence-count">{presence.length}명 접속</span>}
      </button>
      {open && (
        <div className="presence-pop">
          <div className="presence-pop-head">접속 중인 사용자 ({presence.length}명)</div>
          <ul>
            {presence.map((p) => (
              <li key={p.clientKey}>
                <span className="avatar" style={{ background: p.color }}>{p.user.slice(0, 1)}</span>
                <span className="presence-name">{p.user}</span>
                {p.clientKey === clientKey && <span className="presence-me">나</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
