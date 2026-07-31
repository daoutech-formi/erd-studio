import { useState } from "react";

interface Props {
  onClose: () => void;
}

/**
 * 클립보드 복사 — HTTP(비보안 컨텍스트)에서는 navigator.clipboard 가 없으므로
 * 임시 textarea + execCommand 폴백으로 동작시킨다.
 */
async function copyText(text: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 권한 거부 등 — 아래 폴백 시도
    }
  }
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand("copy");
  } catch {
    ok = false;
  }
  document.body.removeChild(ta);
  return ok;
}

/** 등록 명령 등 복사 가능한 코드 한 줄. */
function CodeRow({ code }: { code: string }) {
  const [state, setState] = useState<"idle" | "ok" | "fail">("idle");
  const copy = () => {
    copyText(code).then((ok) => {
      setState(ok ? "ok" : "fail");
      window.setTimeout(() => setState("idle"), 1500);
    });
  };
  return (
    <div className="mcp-code">
      <code>{code}</code>
      <button className="mini" onClick={copy}>
        {state === "ok" ? "복사됨 ✓" : state === "fail" ? "복사 실패" : "복사"}
      </button>
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
        <h2>🔗 Claude 연결 (MCP)</h2>
        <p>
          Claude를 이 ERD Studio에 연결하면, 터미널에서 Claude와 대화하는 것만으로
          <b> DDL 임포트·도메인 자동 분류·스키마 정리</b>를 시킬 수 있습니다.
          예를 들어 <i>"이 DDL 파일을 임포트하고 도메인을 의미 기반으로 분류해줘"</i> 라고 말하면
          Claude가 알아서 처리하고, 결과는 접속 중인 모든 팀원 화면에 실시간 반영됩니다.
          (개인 Claude 라이선스로 동작 — 별도 API 키 불필요)
        </p>

        <div className="mcp-sect">준비물</div>
        <p className="mcp-note">
          ① <b>Claude Code</b>가 설치되어 있어야 합니다 — 터미널(cmd/PowerShell)에
          <code> claude</code> 를 입력했을 때 실행되면 준비된 것입니다.
          ② 사내망에 연결된 PC여야 합니다.
          ③ Claude 앱(Desktop)이 아니라 <b>터미널의 Claude Code</b>로 사용합니다
          — Desktop 커넥터는 사내망 서버에 접속할 수 없습니다.
        </p>

        <div className="mcp-sect">STEP 1 — 연결 등록 (최초 1회)</div>
        <p className="mcp-note">
          터미널을 열고 아래 명령을 실행합니다. <b>어느 폴더에서 실행해도 상관없습니다</b> —
          이 명령은 폴더와 무관한 사용자 설정이며, <code>--scope user</code> 덕분에
          등록 후에는 어떤 폴더에서 Claude를 열어도 연결이 유지됩니다.
        </p>
        <CodeRow code={addCommand} />

        <div className="mcp-sect">STEP 2 — 연결 확인</div>
        <p className="mcp-note">
          아래 명령을 실행해서 <code>erd-studio … ✓ Connected</code> 가 보이면 성공입니다.
          (Connected 가 아니면 사내망 연결과 서버 상태를 확인하세요)
        </p>
        <CodeRow code="claude mcp list" />

        <div className="mcp-sect">STEP 3 — 사용하기</div>
        <p className="mcp-note">
          <b>DDL 파일을 사용할 경우</b>, 그 파일이 있는 폴더로 이동해서 Claude를 실행하세요.
          대화 중 <code>@파일명</code> 참조가 <b>Claude를 실행한 폴더 기준</b>이기 때문입니다.
          (<code>@</code> 를 입력하면 폴더 안 파일이 자동완성으로 뜹니다)
        </p>
        <CodeRow code={"cd D:\\작업폴더\\ddl파일있는곳"} />
        <CodeRow code="claude" />
        <p className="mcp-note">
          Claude가 열리면 아래 예시처럼 <b>그냥 말하면 됩니다.</b> 도구 이름을 외울 필요 없이
          Claude가 알아서 적절한 기능을 호출합니다. 파일 없이 DDL 내용을 대화에 직접
          붙여넣어도 됩니다.
        </p>
        {PROMPTS.map(([label, prompt]) => (
          <div key={label}>
            <div className="mcp-hint">{label}</div>
            <CodeRow code={prompt} />
          </div>
        ))}

        <div className="mcp-sect">참고 — 서버 정보와 제공 기능</div>
        <table className="mcp-table">
          <tbody>
            <tr><th>서버 이름</th><td>erd-studio</td></tr>
            <tr><th>트랜스포트</th><td>SSE (Server-Sent Events)</td></tr>
            <tr><th>SSE 엔드포인트</th><td><code>{endpoint}</code></td></tr>
          </tbody>
        </table>
        <table className="mcp-table" style={{ marginTop: 8 }}>
          <tbody>
            {TOOLS.map(([name, desc]) => (
              <tr key={name}><th><code>{name}</code></th><td>{desc}</td></tr>
            ))}
          </tbody>
        </table>
        <p className="mcp-note">
          인증 토큰이 설정된 서버라면 STEP 1 명령 뒤에
          <code> --header "Authorization: Bearer &lt;토큰&gt;"</code> 을 붙입니다.
        </p>

        <div className="mcp-sect">연결 해제 (삭제)</div>
        <p className="mcp-note">
          더 이상 사용하지 않을 때 아래 명령으로 등록을 삭제합니다. 역시 아무 폴더에서나 실행하면
          되고, <b>서버의 ERD 데이터에는 영향이 없습니다.</b> 삭제 후 <code>claude mcp list</code> 로
          목록에서 사라졌는지 확인하세요.
        </p>
        <CodeRow code="claude mcp remove erd-studio" />

        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button className="mini" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  );
}
