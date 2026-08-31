# 실행 프롬프트 — Phase 5: WebSocket·MCP 권한 정합

> 대상: `/Users/formi/adcon-erd`. 선행: Phase 1~4 머지 완료.
> HTTP API 는 Phase 3 인터셉터가 지키지만 **WebSocket(/ws)과 MCP(/sse, /message)는 인터셉터를 타지 않는다** —
> SSO 를 켜면 뷰어가 WS 로 실시간 편집이 가능하고 MCP 가 전체 방을 노출하는 알려진 공백을 이 페이즈가 닫는다.

## 1. 요구사항

**목적:** SSO 가 켜진 환경에서 ① 방 프로젝트의 READ 권한이 없으면 WS 입장 거부 ② 뷰어(READ)는 WS 편집 메시지(op/lock/move) 거부 ③ MCP 는 개인 토큰으로 계정과 연결되어 자기 프로젝트의 방만 보고·고친다. **`erd.oidc.enabled=false` 면 기존 동작 100% 유지**(WS 전원 편집 가능, MCP 는 ERD_MCP_TOKEN 규칙 그대로).

**WebSocket:**

- 핸드셰이크에서 `erd_session` 쿠키를 해석해 Principal 을 WS 세션 attributes 에 싣는다(HandshakeInterceptor — 거부는 안 하고 신원만 싣는다).
- `hello` 처리 시 방의 소속 프로젝트에 대한 유효 권한을 계산한다:
  - `READ` 미만(게스트·비멤버) → fatal 메시지("로그인이 필요합니다." / "이 프로젝트에 대한 권한이 없습니다…") 후 종료 — 프론트 onFatal 이 방 목록으로 돌려보낸다.
  - 통과 시 계산된 Level 을 세션 attributes 에 저장(재조회 없이 op 마다 사용).
- `op`/`lock`/`move` 처리 시 저장된 Level 이 `WRITE` 미만이면 error 메시지("뷰어 권한으로는 편집할 수 없습니다.")로 거부.
- 역할이 도중에 바뀌면 **재입장 시** 반영된다(세션 유지 중 실시간 강등은 범위 밖).

**MCP — 개인 토큰으로 계정 연결:**

- `app_user.mcp_token_hash`(unique, SHA-256 hex) 추가. 원문 토큰은 DB 에 저장하지 않는다(세션과 동일 규칙).
- `POST /api/auth/mcp-token` — 로그인 사용자가 자기 토큰을 발급/재발급(기존 토큰 무효화). 응답 `{"token":"..."}` — **이때 한 번만 원문 노출**. 게스트는 401.
- MCP 요청(`GET /sse`, `POST /message`)의 Bearer 해석 순서:
  1. `ERD_MCP_TOKEN`(서버 토큰)과 일치 → **superAdmin 급 주체**(기존 운영·배치용, 전체 접근).
  2. 개인 토큰 해시와 일치 → 그 계정의 Principal.
  3. 둘 다 아니면 — oidc **disabled + 서버 토큰 미설정**이면 기존처럼 개방(게스트, disabled 라 전권), 그 외 401.
- 도구별 요구 수준: `list_rooms` = 보이는 프로젝트의 방만(필터), `create_room` = legacy 프로젝트 WRITE(MCP 는 프로젝트 컨텍스트가 없어 legacy 에 만든다 — 현행 유지), `get_schema`/`preview_ddl` = 방 프로젝트 READ, `replace_schema`/`import_ddl` = 방 프로젝트 WRITE.
- 권한 거부는 JSON-RPC 오류가 아니라 **isError=true 결과**(모델이 읽고 사용자에게 설명하도록), 메시지는 401/403 구분 없이 안내문.

**프론트:**

- `McpGuideModal` — oidcEnabled 환경이면 "개인 토큰" 섹션: 로그인 사용자에게 발급 버튼 → 토큰 1회 표시 + `--header "Authorization: Bearer …"` 포함 등록 명령 안내. 재발급 시 기존 연결이 끊긴다는 경고.

**수용 기준:**

- [ ] 단위 테스트: MCP 토큰 — 발급→해석(Principal 복원), 재발급 시 기존 토큰 무효, 게스트 발급 401.
- [ ] 단위 테스트: MCP 도구 권한(enabled 시뮬레이션) — 비멤버 list_rooms 빈 목록·get_schema 거부, 뷰어 get_schema 허용·replace_schema 거부, superAdmin 전체.
- [ ] `./gradlew test` 통과(기존 실패 6건 제외), `npx tsc --noEmit` 통과.
- [ ] disabled 회귀: 브라우저 WS 편집(방 입장·op 반영), MCP `list_rooms`(전체 노출) 기존과 동일.
- [ ] (수동, SSO 켠 후) 뷰어 계정은 방에 들어가져도 편집 메시지가 거부되고, 비멤버는 WS 입장이 거부된다. 개인 토큰 없는 MCP 는 401.

**범위 밖:** WS 세션 유지 중 실시간 권한 강등, MCP 의 프로젝트 선택(legacy 고정 현행 유지), 토큰 만료 정책(재발급으로 갈음).

## 2. 개발디테일

신규 — 백엔드:
- `ws/WsAuthHandshakeInterceptor.java` — ServletServerHttpRequest 에서 erd_session 쿠키 → authService.resolve → attributes("principal").

변경 — 백엔드:
- `config/WebSocketConfig` — addInterceptors 등록.
- `ws/ErdSocketHandler` — hello 에서 levelFor 검사(READ)·attributes("level") 저장, op/lock/move 에서 WRITE 검사. 방 프로젝트는 room.projectId(null→legacy).
- `auth/AppUser` — mcpTokenHash 필드 + rotateMcpToken(hash). `auth/AppUserRepository` — findByMcpTokenHash.
- `auth/AuthService` — issueMcpToken(principal)(401 검사 포함)/resolveMcpToken(raw→Principal|null).
- `auth/AuthController` — `POST /api/auth/mcp-token`.
- `web/mcp/McpSseController` — authorized() → resolvePrincipal(위 순서), message 처리 시 tools.call 에 Principal 전달, Unauthorized/Forbidden 을 isError 결과로.
- `web/mcp/McpToolService` — call(name, args, principal), listRooms 필터(visibleProjects), 방 도구 require(READ/WRITE), create_room 은 legacy WRITE.

변경 — 프론트: `http.ts`(issueMcpToken), `McpGuideModal.tsx`(me prop + 개인 토큰 섹션), `RoomList.tsx`(me 전달).

## 3. 제약사항

- disabled 에서 회귀 0 — WS·MCP 모두 기존 응답 그대로(levelFor 가 disabled→ADMIN 이므로 검사가 자연 통과해야 한다).
- 신규 의존성·Lombok 금지. 토큰 원문·해시를 로그에 남기지 않는다.
- WS 권한 판정은 서버(핸들러)가 강제 — 프론트의 편집 UI 숨김은 보조.
- 서버 토큰(ERD_MCP_TOKEN) 기존 사용처가 깨지지 않아야 한다(설정돼 있으면 지금처럼 전체 접근).

## 4. 테스트방법

`./gradlew test`(신규 McpTokenTest·McpToolService 권한 테스트 포함) → `npx tsc --noEmit` → `docker-compose up -d --build`(standalone CLI) 후 disabled 회귀: curl `/sse` 200 + `list_rooms` 전체 노출, 브라우저(클로드인크롬)로 방 입장·편집 정상 확인 → 커밋. SSO 실환경 검증은 authentik 등록 후 수동.

---
**다음:** authentik 앱 등록 후 `ERD_OIDC_ENABLED=true` 실검증(전 페이즈 수동 시나리오).
