import { useState } from "react";

interface Props {
  onClose: () => void;
}

/** 등록 명령 등 복사 가능한 코드 한 줄. */
function CodeRow({ code }: { code: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <div className="mcp-code">
      <code>{code}</code>
      <button className="mini" onClick={copy}>{copied ? "복사됨 ✓" : "복사"}</button>
    </div>
  );
}

const TOOLS: Array<[string, string]> = [
  ["list_rooms", "ERD 방 목록 조회 (id·이름·테이블 수)"],
  ["create_room", "방 생성 (전체 최대 20개)"],
  ["get_schema", "방의 전체 스키마 문서 조회"],
  ["replace_schema", "스키마 전체 교체 — 도메인 재구성·테이블 재배치"],
  ["preview_ddl", "DDL 파싱 후 변경 요약 미리보기 (반영 없음)"],
  ["import_ddl", "DDL 을 방에 적용 (merge/replace)"],
];

const PROMPTS: Array<[string, string]> = [
  ["방 목록 확인", "erd-studio의 list_rooms로 방 목록 보여줘"],
  ["방 생성 + DDL 임포트",
    "erd-studio로 '결제 시스템 ERD' 방을 새로 만들고, @payment_ddl.sql 을 임포트해서 도메인 분류까지 해줘"],
  ["DDL 임포트 + 도메인 재분류",
    "@create-tables.sql 을 '애드콘 ERD' 방에 merge로 임포트하고, 도메인은 테이블 의미를 분석해서 재분류해줘"],
  ["도메인 구성 검토", "3번 방 스키마를 조회해서 도메인 분류가 적절한지 검토하고, 어색한 테이블은 옮겨줘"],
  ["스키마 요약", "1번 방 스키마에서 핵심 테이블과 관계 구조를 요약해줘"],
];

/**
 * MCP 연결 안내 — 접속 중인 화면 주소로 SSE 엔드포인트를 자동 감지한다.
 * 운영은 프론트·백엔드가 같은 오리진이므로 배포 도메인이 그대로 표시되고,
 * 개발(localhost:5173)에서는 Vite 프록시가 /sse 를 백엔드로 넘기므로 역시 그대로 동작한다.
 */
export function McpGuideModal({ onClose }: Props) {
  const endpoint = `${window.location.origin}/sse`;
  const addCommand = `claude mcp add --transport sse --scope user erd-studio ${endpoint}`;

  return (
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal mcp-guide">
        <h2>🔗 MCP 연결</h2>
        <p>
          이 서버는 MCP(SSE)로도 노출됩니다. Claude Code에 연결하면 <b>구독 라이선스의 Claude가
          DDL 의미 분석·도메인 분류를 수행</b>하고 도구 호출로 방에 직접 반영합니다 (API 키 불필요).
          반영 결과는 접속 중인 모든 브라우저에 실시간 전파됩니다.
        </p>

        <div className="mcp-sect">서버 정보</div>
        <table className="mcp-table">
          <tbody>
            <tr><th>서버 이름</th><td>erd-studio</td></tr>
            <tr><th>트랜스포트</th><td>SSE (Server-Sent Events)</td></tr>
            <tr><th>SSE 엔드포인트</th><td><code>{endpoint}</code></td></tr>
          </tbody>
        </table>

        <div className="mcp-sect">Claude Code (CLI) 연결 — 터미널에서 실행</div>
        <CodeRow code={addCommand} />
        <div className="mcp-hint">연결 확인:</div>
        <CodeRow code="claude mcp list" />
        <p className="mcp-note">
          인증 토큰이 설정된 서버라면 명령 뒤에 <code>--header "Authorization: Bearer &lt;토큰&gt;"</code> 을
          붙입니다. Claude Desktop의 원격 커넥터는 Anthropic 클라우드를 경유해 사내망 서버에 접속할 수
          없으므로 <b>Claude Code CLI</b>를 사용하세요.
        </p>

        <div className="mcp-sect">제공 tool</div>
        <table className="mcp-table">
          <tbody>
            {TOOLS.map(([name, desc]) => (
              <tr key={name}><th><code>{name}</code></th><td>{desc}</td></tr>
            ))}
          </tbody>
        </table>

        <div className="mcp-sect">질의 예시 — Claude Code 대화에 붙여넣기</div>
        {PROMPTS.map(([label, prompt]) => (
          <div key={label}>
            <div className="mcp-hint">{label}</div>
            <CodeRow code={prompt} />
          </div>
        ))}
        <p className="mcp-note">
          <code>@파일</code> 참조는 Claude Code를 실행한 폴더 기준입니다. DDL 파일이 있는 폴더에서
          <code>claude</code> 를 실행하면 자동완성으로 파일을 고를 수 있습니다.
        </p>

        <div className="mcp-sect">연결 해제 (삭제)</div>
        <CodeRow code="claude mcp remove erd-studio" />
        <p className="mcp-note">
          등록을 삭제해도 서버의 ERD 데이터에는 영향이 없습니다. 삭제 후 목록에서 사라졌는지는
          <code> claude mcp list</code> 로 확인합니다. 다른 이름으로 등록했다면 그 이름으로,
          여러 스코프에 중복 등록했다면 <code>--scope user</code> 등 스코프를 붙여 각각 삭제합니다.
        </p>

        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button className="mini" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  );
}
