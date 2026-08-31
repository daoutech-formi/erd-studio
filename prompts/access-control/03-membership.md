# 실행 프롬프트 — Phase 3: 멤버십·권한·가시성

> 대상: `/Users/formi/adcon-erd`. 선행: Phase 1(SSO)·Phase 2(프로젝트) 머지 완료.
> 참고 원본: `/Users/formi/cortex` 의 `auth/PermissionService.java`(대폭 단순화해 이식), `auth/ProjectMember*`.

## 1. 요구사항

**목적:** "권한 있는 사람만 자기 프로젝트를 본다"를 실현한다. 프로젝트별 멤버십(관리자/편집자/뷰어)을 도입하고, SSO 가 켜진 환경에서는 ① 내가 멤버인 프로젝트만 목록에 보이고 ② 비멤버·게스트의 접근은 401/403 이며 ③ 뷰어는 읽기만 가능하다. **`erd.oidc.enabled=false` 면 기존(전원 전권) 동작 100% 유지** — 이 페이즈까지 배포해도 SSO 를 켜기 전엔 아무 변화가 없다.

**권한 모델(cortex 를 단순화):**

- `Level` : `NONE < READ < WRITE < ADMIN` (ordinal 비교).
- `ProjectRole` : `ADMIN`(멤버·프로젝트 관리+쓰기) / `EDITOR`(쓰기) / `VIEWER`(읽기) → Level 매핑.
- 유효 권한 계산 `PermissionService.levelFor(principal, projectId)`:
  ① oidc disabled → `ADMIN`(기존 동작) ② superAdmin → `ADMIN` ③ 게스트 → `NONE`
  ④ project_member 행의 role ⑤ 비멤버 로그인 사용자 → `NONE`.
- 판정 대상 프로젝트: `/api/rooms/{id}/**` 는 **URL 의 방이 속한 프로젝트**(헤더만 믿으면 남의 방 id 로 우회 가능), `/api/rooms` 목록·생성은 현재 프로젝트(X-Project-Id).

**엔드포인트별 요구 수준(`PermissionInterceptor`, oidc enabled 일 때만):**

| 경로 | 요구 |
|---|---|
| `GET /api/projects` | 통과(컨트롤러가 가시성 필터) |
| `POST /api/projects` | 로그인(게스트 401). 생성자는 자동 ADMIN 멤버 |
| `DELETE /api/projects/{slug}`, `GET·PUT /{slug}/members`, `GET /{slug}/assignable-users` | 해당 프로젝트 ADMIN |
| `GET /api/rooms`, `GET /api/rooms/{id}(/**)` | 해당 프로젝트 READ |
| `POST /api/rooms`, 그 외 메서드 `/api/rooms/{id}(/**)` | 해당 프로젝트 WRITE |
| `PUT /api/rooms/creator-name` | 통과(게스트용 표시명 기능) |
| 존재하지 않는 roomId | 통과시켜 컨트롤러가 기존대로 400(정보 노출 없음) |

- 게스트 → `401 {"error":...}` (프론트가 로그인 유도), 로그인했지만 권한 부족 → `403`.

**멤버 관리 API:**

- `GET /api/projects/{slug}/members` → `[{userId,username,displayName,role}]`
- `PUT /api/projects/{slug}/members` 본문 `{"members":[{"userId":1,"role":"ADMIN"},...]}` — 통째 교체(cortex 방식). **새 목록에 ADMIN 이 1명 이상** 없으면 400(스스로 잠금 방지. superAdmin 은 언제나 관리 가능).
- `GET /api/projects/{slug}/assignable-users` → superAdmin 제외 전 계정 `[{id,username,displayName}]`.

**수용 기준(자동 검증 가능한 것 — oidc disabled 환경):**

- [ ] disabled 상태에서 기존 시나리오 전부 회귀 없음: `/api/projects`(전체 노출)·`/api/rooms`·방 생성/삭제·멤버 API 까지 모두 200(전원 ADMIN 취급).
- [ ] 단위 테스트: `PermissionService` — disabled→ADMIN / superAdmin→ADMIN / 게스트→NONE / 멤버 role 매핑 / 비멤버→NONE / `require` 403·401.
- [ ] 단위 테스트: 가시성 — superAdmin 전부, 멤버는 자기 프로젝트만, 게스트 빈 목록(enabled 시뮬레이션은 프로퍼티 주입으로).
- [ ] 단위 테스트: 멤버 교체 — ADMIN 0명이면 거부, 프로젝트 생성자가 ADMIN 멤버로 자동 등록, 프로젝트 삭제 시 멤버십도 삭제.
- [ ] `./gradlew test` 통과(기존 실패 6건 제외), `npx tsc --noEmit` 통과.
- [ ] (수동, SSO 켠 후) 비멤버 계정으로 로그인하면 프로젝트 목록이 비어 있고, 자기가 만든 프로젝트만 보인다. 뷰어는 방 열람만 되고 저장·임포트는 403.

**범위 밖:** 초대 링크(Phase 4), WebSocket·MCP 권한(Phase 5 — enabled 여도 뷰어가 WS 실시간 편집은 가능한 알려진 공백), 계정 관리 UI(superAdmin 부여 등 — authentik 그룹으로 대체), 프로젝트 이름 변경.

**formi 소유 부트스트랩:** 코드가 아니라 운영 설정으로 해결한다 — formi 를 authentik `erd-admins` 그룹에 넣으면 superAdmin 으로 전 프로젝트(legacy 포함) 관리가 가능하다. legacy 는 목록에서 superAdmin·멤버에게만 보이므로 "기존 방은 내 소유, 나머지에겐 비공개" 요구가 충족된다.

## 2. 개발디테일

신규 — `backend/src/main/java/com/daou/erdstudio/project/`:
- `Level.java`, `ProjectRole.java`(+`level()`), `ProjectMember.java`(BaseTimeEntity, UNIQUE(project_id,user_id)), `ProjectMemberRepository.java`(findByProjectIdAndUserId/findByProjectId/findByUserId/deleteByProjectId/deleteByUserId)
- `PermissionService.java` — levelFor/require(403·401)/visibleProjects(principal)/멤버 조회·교체(replace, ADMIN≥1 검증)/assignable
- `PermissionInterceptor.java` — 위 표대로. order 2, `/api/**`(auth·health 제외)

신규 — `backend/src/main/java/com/daou/erdstudio/common/`:
- `ForbiddenException.java`, `UnauthorizedException.java` + `web/ApiExceptionHandler` 에 403·401 핸들러 추가

변경 — 백엔드: `ProjectController`(list 가시성 필터 + `myRole` 필드, create 시 ADMIN 멤버 등록, delete 시 멤버십 정리, members·assignable-users 엔드포인트), `ProjectService.delete`(멤버십 삭제), `WebConfig`(인터셉터 order 2 등록)

변경 — 프론트: `http.ts`(Project.myRole, ProjectMemberInfo/AssignableUser 타입, fetchMembers/replaceMembers/fetchAssignableUsers), `RoomList.tsx`(미로그인+enabled → 로그인 안내 패널, 프로젝트 없음 안내, ADMIN 이면 "👥 멤버" 버튼, 뷰어 프로젝트 입장 시 읽기전용으로), `MembersModal.tsx` 신규(기존 모달 CSS 재사용), `App.tsx`(enterRoom 에 뷰어 읽기전용 전달)

## 3. 제약사항

- disabled 에서 회귀 0(전 엔드포인트 기존 응답 그대로). 신규 의존성·Lombok 금지.
- 권한 판정은 반드시 서버(인터셉터)가 강제하고 프론트 숨김은 보조. room 경로는 URL roomId 기준 프로젝트로 판정(헤더 우회 차단).
- superAdmin 은 모든 검사를 통과하되 멤버 교체의 ADMIN≥1 규칙은 그대로 적용.

## 4. 테스트방법

`./gradlew test`(신규 PermissionServiceTest 포함) → `npx tsc --noEmit` → `docker-compose up -d --build` 후 disabled 회귀 curl(프로젝트/방/멤버 API 200) → 커밋. SSO 실환경 검증은 authentik 등록 후 수동.

---
**다음 페이즈:** `04-invite.md` (초대 링크/코드).
