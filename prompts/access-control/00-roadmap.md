# 접근 권한(SSO · 프로젝트 · 멤버십) 도입 — 로드맵

> ERD Studio(adcon-erd)에 "로그인한 사람이, 자기가 속한 프로젝트의 ERD만 본다"를 도입한다.
> 인증·프로젝트·권한 모델은 사내 **cortex**(`/Users/formi/cortex`)에 이미 구현된 것을 이식·단순화해 재사용한다.
> 각 페이즈는 독립적으로 배포·검증 가능하도록 쪼갰다. 페이즈별 실행 프롬프트는 `NN-*.md` 로 따로 둔다.

---

## 0. 확정된 결정 (사용자 답변)

| 항목 | 결정 |
|---|---|
| 인증 방식 | **authentik OIDC SSO** — cortex의 `auth` 패키지 이식(Authorization Code + PKCE) |
| 권한 등급 | **관리자(ADMIN) / 편집자(EDITOR) / 뷰어(VIEWER)** — 프로젝트당 고정 3역할 |
| 합류 방식 | **초대 링크/코드 공유** — 관리자가 링크 생성, 링크 받은 사람이 로그인 후 자동 합류 |
| 기존 방(ERD) | **내 계정(formi) 소유의 레거시 프로젝트로 이동, 나머지 계정엔 비공개** |

---

## 1. 현재 상태 (adcon-erd) 요약

- 패키지 `com.daou.erdstudio`, Spring Boot(web + data-jpa + websocket) + React(Vite).
- **인증 없음.** "사용자"는 `NameModal`에 입력해 `localStorage`에 저장하는 이름일 뿐이고, `X-User` 헤더로 이름만 전달된다. 서버에 계정/비밀번호/세션 개념이 전무하다.
- ERD = `erd_room`(방)이 최상위 단위. `GET /api/rooms`가 **모든 방을 전원에게** 내려준다.
- **프로젝트 개념 없음.**
- 스키마는 **Hibernate `ddl-auto: update`로 자동 생성**(Flyway 미사용). → 새 테이블은 `@Entity`로 자동 생성되지만, 기존 테이블 컬럼 추가·데이터 백필은 자동으로 안 되므로 **부트스트랩 `ApplicationRunner`(멱등)** 로 처리한다.
- 실시간 협업 WebSocket(`ErdSocketHandler`, `/ws/erd`)과 MCP SSE 엔드포인트(`/sse`, `/mcp/**`)가 있다 — 인터셉터를 타지 않으므로 권한 검사는 별도 처리(Phase 5).

## 2. cortex에서 그대로/거의 그대로 가져올 것

경로: `/Users/formi/cortex/backend/src/main/java/com/daou/cortex/`

- `auth/OidcController.java` `auth/OidcService.java` `auth/OidcClient.java` `auth/OidcLoginException.java` — OIDC 로그인 진입/콜백/코드교환/PKCE. **거의 그대로**(패키지·쿠키명만 변경).
- `auth/AuthService.java` `auth/Principal.java` `auth/AuthContext.java` `auth/AuthInterceptor.java` — 세션 발급/해석, 요청 주체 ThreadLocal. **그대로**.
- `auth/AppUser.java` + `app_user` / `auth_session` 스키마 — **그대로**(엔티티로 자동 생성).
- `project/ProjectContext.java` `project/ProjectInterceptor.java`(X-Project-Id) — **그대로**.
- `auth/PermissionService.java` — **단순화해서** 가져온다(cortex의 role_group·persona·6-feature 격자는 **버리고**, 고정 3역할 + 단일 기능(ERD)만).
- `frontend/src/api.ts`(X-Project-Id 전파·`me`·`logout`), `frontend/src/App.tsx`(로그인 게이트), `frontend/src/components/AccessAdmin.tsx`(권한 관리 UI) — **패턴 참고**.
- 참고 프롬프트: `/Users/formi/cortex/prompts/account-role.md` — 이 문서·페이즈 프롬프트의 서식(요구사항/개발디테일/제약/테스트)이 그 스타일이다.

### cortex와 다르게 갈 점 (단순화 / 신규)

- cortex는 role_group + 기능별 NONE/READ/WRITE 격자까지 있으나 ERD Studio는 **기능이 사실상 하나(ERD)** 라 과하다. → `project_member.role` = **ADMIN/EDITOR/VIEWER 고정**으로 끝낸다. `role_group*`, `project_guest_permission`, persona는 **가져오지 않는다**.
- cortex엔 **초대 링크가 없다**(관리자가 계정을 직접 배정). ERD Studio는 초대 링크가 요구사항이므로 `project_invite` 테이블과 생성/수락 플로우를 **신규** 구현한다(Phase 4).
- cortex는 Flyway, adcon-erd는 ddl-auto → **엔티티 자동 생성 + 부트스트랩 러너**로 대체.
- 쿠키/설정 네이밍: `cortex_session`→`erd_session`, `cortex_oidc`→`erd_oidc`, `cortex.oidc.*`→`erd.oidc.*`, `CORTEX_OIDC_*`→`ERD_OIDC_*`.

---

## 3. 목표 데이터 모델

```
app_user            로그인 계정 (control-plane, 프로젝트 격리 대상 아님)
 ├ id, username, display_name, oidc_sub(unique), super_admin, super_admin_granted
auth_session        세션 (쿠키 토큰의 SHA-256 해시만 저장)
 ├ id, token_hash(unique), user_id→app_user, expires_at
project             프로젝트 = ERD 묶음 (격리 단위)
 ├ id, slug(unique), name, created_at
project_member      멤버십 + 역할
 ├ id, project_id→project, user_id→app_user, role(ADMIN/EDITOR/VIEWER), UNIQUE(project_id,user_id)
project_invite      초대 링크/코드 (Phase 4)
 ├ id, project_id→project, token(unique), role, expires_at, max_uses, used_count, created_by
erd_room            (기존) + project_id→project   ← 방이 프로젝트에 소속
```

**권한 규칙(유효 권한 계산):**
1. `super_admin` → 전 프로젝트 관리·읽기·쓰기 전부.
2. 프로젝트 멤버:
   - `ADMIN` = 해당 프로젝트의 방 읽기/쓰기 + **멤버·초대 관리**.
   - `EDITOR` = 방 읽기/쓰기 (관리 불가).
   - `VIEWER` = 방 읽기만 (쓰기·삭제·임포트 403).
3. 비멤버(로그인했지만 그 프로젝트 멤버가 아님) = 그 프로젝트 **안 보임**(목록 제외) + 모든 접근 403.
4. `erd.oidc.enabled=false`(SSO 미설정) = **전원 게스트, 기존 동작 100% 유지**(가림 없음). 이 스위치로 페이즈별 점진 적용/롤백이 가능하다.

**"쓰기(WRITE)"의 정의(ERD Studio 특성):** HTTP 메서드 기준 — `GET`/`HEAD`=READ, 그 외(POST/PUT/DELETE 및 스키마 저장·DDL/Smart 임포트·이력 복원)=WRITE. **단, 실시간 편집은 WebSocket으로 흐르므로 Phase 5에서 별도 검사**한다(그전까지 VIEWER도 WS로는 편집 가능 — 알려진 공백).

---

## 4. 페이즈 로드맵

| # | 페이즈 | 무엇을 얻나 | 끝났을 때 확인 |
|---|---|---|---|
| 1 | **SSO 로그인 토대** | authentik로 로그인/로그아웃, `/api/auth/me`, 세션 쿠키. 아직 아무것도 차단 안 함. | 로그인 왕복 성공, me에 내 계정 표시, 로그아웃 후 게스트. `enabled=false`면 기존과 동일 |
| 2 | **프로젝트 + 방 소속화** | project 테이블, 방이 프로젝트에 소속, 프로젝트 선택 UI, 방 목록이 프로젝트별로 갈림. 기존 방→레거시 프로젝트 백필. | 프로젝트를 바꾸면 방 목록이 달라짐. 기존 방은 전부 레거시 프로젝트에 있음 |
| 3 | **멤버십·권한·가시성** | project_member, 관리자/편집자/뷰어, "내가 속한 프로젝트만" 목록, 뷰어 쓰기 차단, 관리 UI. formi=레거시 프로젝트 ADMIN 부트스트랩. | 비멤버는 프로젝트 목록에서 안 보임. 뷰어는 방 저장 403. formi만 레거시 프로젝트 관리 |
| 4 | **초대 링크/코드** | 관리자가 역할 지정 초대 링크 생성, 링크로 로그인→자동 합류. | 링크로 접속·로그인하면 지정 역할의 멤버가 됨. 만료·사용횟수 초과 링크는 거절 |
| 5 | **WS·MCP 권한 정합** | `/ws/erd` 접속 시 READ, 편집 op에 WRITE 검사. MCP 토큰↔계정 연결. | 뷰어는 실시간 편집 불가. 비멤버는 WS 접속 거부 |

> **개발 순서 원칙:** 반드시 1→2→3 순. 4·5는 3 이후 독립적. 각 페이즈는 `erd.oidc.enabled` 플래그로 켜기 전까지 기존 사용자에게 영향 없음.

## 5. 착수 전 준비물 (사용자/인프라)

- **authentik에 OIDC 애플리케이션·Provider 등록** 필요:
  - client_id / client_secret 발급
  - redirect_uri = `https://<erd-도메인>/api/auth/oidc/callback` 등록
  - 최고관리자 그룹명(예: `erd-admins`) — 이 그룹 소속은 자동 super_admin
  - issuer URL(예: `https://<authentik>/application/o/<slug>/`)
  - cortex가 이미 authentik을 쓰므로 **같은 authentik 인스턴스에 앱 하나 더 추가**하면 된다.
- 배포에 주입할 env: `ERD_OIDC_ENABLED=true`, `ERD_OIDC_ISSUER`, `ERD_OIDC_CLIENT_ID`, `ERD_OIDC_CLIENT_SECRET`, `ERD_OIDC_REDIRECT_URI`, `ERD_OIDC_ADMIN_GROUP`.
- 로컬 개발/테스트는 `ERD_OIDC_ENABLED=false`로 두면 인증 없이 기존처럼 돌아간다(단위 테스트로 로직 검증).

## 6. 범위 밖(전 페이즈 공통, 명시적 비목표)

- 자체 회원가입·비밀번호·이메일 인증(전부 authentik 위임).
- 방(ERD) 단위 개별 권한 — 권한은 **프로젝트 단위**까지만.
- 감사 로그(누가 언제 무엇을 바꿨는지). ※ `erd_history`는 이미 있으나 권한 관리 로그는 별개.
- cortex의 role_group / 기능별 격자 / persona 프롬프트.
