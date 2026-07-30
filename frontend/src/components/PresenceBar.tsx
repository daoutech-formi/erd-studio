import { useStore } from "../state/schemaStore";

/** 접속자 배지 목록 — 이니셜 원 + 이름 툴팁, 연결 상태 점. */
export function PresenceBar() {
  const { presence, connected } = useStore();
  return (
    <div className="presence" title={connected ? "실시간 연결됨" : "연결 끊김 — 재접속 중"}>
      <span className={`conn-dot${connected ? " on" : ""}`} />
      {presence.map((p) => (
        <span key={p.clientKey} className="avatar" style={{ background: p.color }} title={p.user}>
          {p.user.slice(0, 1)}
        </span>
      ))}
      {presence.length > 0 && <span className="presence-count">{presence.length}명 접속</span>}
    </div>
  );
}
