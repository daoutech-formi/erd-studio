# 실행 프롬프트 — Phase 4: 초대 링크/코드

> 대상: `/Users/formi/adcon-erd`. 선행: Phase 1(SSO)·Phase 2(프로젝트)·Phase 3(멤버십·권한) 머지 완료.
> cortex 엔 초대 기능이 없어 이 페이즈는 **신규 설계**다(로드맵 §2 참고). 기존 auth/project 패키지 스타일을 따른다.

## 1. 요구사항

**목적:** 프로젝트 관리자가 **역할이 지정된 초대 링크**를 만들어 공유하면, 링크를 받은 사람이 접속·로그인해 그 역할의 멤버로 **자동 합류**한다. 지금은 관리자가 멤버 모달에서 계정을 직접 골라야 하는데(이미 로그인해 본 적 있는 계정만 목록에 뜸), 초대 링크는 **아직 한 번도 로그인 안 한 사람도** 합류시킬 수 있다. **`erd.oidc.enabled=false` 면 기존 동작 100% 유지** — 초대 UI 는 숨겨지고 초대 API 는 존재하되 로그인이 없으므로 수락이 불가능할 뿐, 기존 기능엔 아무 영향이 없다.

**데이터 모델 — `project_invite`(신규 엔티티, ddl-auto 자동 생성):**

- `id`, `project_id`, `token`(unique 64, SecureRandom hex 32자), `role`(ADMIN/EDITOR/VIEWER), `expires_at`(nullable — null 이면 무기한), `max_uses`(0 = 무제한), `used_count`, `created_by`(만든 계정 id), created/updated(BaseTimeEntity).
- 초대 URL 은 `<origin>/#/invite/{token}` — 해시 라우트라 서버 라우팅 불요(딥링크와 동일 방식).

**API:**

| 경로 | 요구 권한 | 동작 |
|---|---|---|
| `GET /api/projects/{slug}/invites` | 프로젝트 ADMIN(기존 인터셉터 규칙) | 초대 목록 `[{id,token,role,expiresAt,maxUses,usedCount,exhausted}]` |
| `POST /api/projects/{slug}/invites` | 프로젝트 ADMIN | 본문 `{"role":"VIEWER","expiresInDays":7,"maxUses":0}` → 생성. expiresInDays null=무기한 |
| `DELETE /api/projects/{slug}/invites/{id}` | 프로젝트 ADMIN | 초대 폐기(그 링크는 즉시 무효) |
| `GET /api/invites/{token}` | **게스트 허용** | 미리보기 `{projectName,role,valid,reason?,alreadyMember}` — 로그인 전 "무엇에 초대됐는지" 표시용 |
| `POST /api/invites/{token}/accept` | 로그인(게스트 401) | 검증 통과 시 멤버 등록 + used_count 증가, `{ok,projectSlug,projectName,role,alreadyMember}` 반환 |

**수락 검증 규칙(순서대로):**

1. token 미존재 → 400 "유효하지 않은 초대 링크입니다."
2. `expires_at` 경과 → 400 "만료된 초대 링크입니다."
3. `max_uses > 0 && used_count >= max_uses` → 400 "사용 횟수를 모두 소진한 초대 링크입니다."
4. 이미 그 프로젝트 멤버 → **역할 변경 없이** `alreadyMember=true` 로 성공 처리(used_count 증가 없음). 초대 링크가 기존 관리자를 뷰어로 강등시키는 사고 방지.
5. 통과 → `project_member(project_id, user_id, role)` 저장 + `used_count` 증가.

**프론트 플로우:**

- **관리자:** 멤버 모달(MembersModal)에 "🔗 초대 링크" 섹션 추가 — 역할·유효기간(1일/7일/30일/무기한)·최대 사용(무제한/1/5/10) 골라 생성, 기존 링크 목록(역할·만료·사용횟수)에서 **복사**·**폐기**. 링크는 즉시 생성·즉시 폐기(멤버 목록과 달리 저장 버튼과 무관).
- **초대받은 사람:** `#/invite/{token}` 접속 → 미리보기 화면(프로젝트명·역할 표시).
  - 미로그인이면 "로그인 후 참여" 버튼 → **token 을 localStorage(`erd_pending_invite`)에 저장** 후 SSO 로그인으로 이동(로그인 왕복에서 URL 해시가 유실되므로). 로그인 복귀 후 App 이 pending token 을 발견하면 수락 화면을 다시 띄운다.
  - 로그인 상태면 "참여하기" 버튼 → accept → 성공 시 해당 프로젝트로 전환(setProject)하고 방 목록으로.
  - 무효 링크(만료·소진·삭제)는 사유를 보여주고 방 목록으로 돌아가는 버튼만 제공.

**수용 기준:**

- [ ] 단위 테스트: 생성(토큰 유니크·기본값) / 수락 성공(멤버 생성+used_count 증가) / 만료 거절 / 사용횟수 소진 거절 / 기존 멤버 재수락(역할 불변·카운트 불변) / 폐기 후 수락 거절 / 프로젝트 삭제 시 초대도 삭제.
- [ ] `./gradlew test` 통과(기존 실패 6건 제외), `npx tsc --noEmit` 통과.
- [ ] disabled 회귀: `/api/projects`·`/api/rooms`·방 생성/삭제 전부 기존과 동일 200.
- [ ] disabled 에서도 초대 CRUD 는 동작(전원 ADMIN 취급)하되 accept 는 로그인 없음 → 401.
- [ ] (수동, SSO 켠 후) 관리자가 만든 뷰어 초대 링크로 다른 계정이 로그인·합류 → 그 프로젝트가 목록에 보이고 읽기전용.

**범위 밖:** 초대 이메일 발송(링크는 슬랙 등으로 직접 공유), 초대 수락 승인 절차(링크 소지 = 합류), WS·MCP 권한(Phase 5).

## 2. 개발디테일

신규 — `backend/src/main/java/com/daou/erdstudio/project/`:
- `ProjectInvite.java` — BaseTimeEntity. `expired()`/`exhausted()` 판정 메서드, `use()`(used_count++).
- `ProjectInviteRepository.java` — findByToken / findByProjectIdOrderByIdAsc / deleteByProjectId.
- `InviteService.java` — create(projectId, role, expiresInDays, maxUses, creatorUserId) / list(projectId) / revoke(projectId, inviteId — 남의 프로젝트 초대 id 를 못 지우게 projectId 일치 검증) / preview(token, principal) / accept(token, principal). 토큰 = SecureRandom 16바이트 hex.
- `InviteController.java` — 위 5개 엔드포인트. `/api/projects/{slug}/invites*` 는 기존 인터셉터의 `/api/projects/{slug}/**`=ADMIN 규칙이 자동 커버.

변경 — 백엔드:
- `PermissionInterceptor` — `/api/invites/**` 분기 추가: GET 통과(게스트 미리보기), 그 외 메서드는 로그인 요구(401).
- `ProjectService.delete` — `inviteRepository.deleteByProjectId` 추가.

변경 — 프론트:
- `http.ts` — `InviteInfo`/`InvitePreview`/`AcceptResult` 타입, fetchInvites/createInvite(role, expiresInDays, maxUses)/revokeInvite/fetchInvitePreview/acceptInvite. `inviteUrl(token)` 헬퍼.
- `route.ts` — `parseRoute` 가 `#/invite/{token}` 도 인식(Route 에 `inviteToken` 추가).
- `App.tsx` — inviteToken 상태(초기값 라우트에서). **me 조회 후** localStorage `erd_pending_invite` 가 있고 로그인돼 있으면 그걸 inviteToken 으로 승격·제거. inviteToken 이 있으면 NameModal·RoomList 대신 `InviteAccept` 렌더(로그인 사용자는 이름이 계정에서 오므로 NameModal 불요).
- `InviteAccept.tsx` 신규 — 미리보기 로드, 미로그인 → localStorage 저장 후 로그인 이동, 로그인 → 참여하기 → setProject(projectSlug) 후 해시 제거·방 목록으로.
- `MembersModal.tsx` — 초대 링크 섹션(목록·생성·복사(`utils/clipboard`의 copyText)·폐기).

## 3. 제약사항

- disabled 에서 회귀 0. 신규 의존성·Lombok 금지. 토큰 원문은 DB 에 그대로 둬도 무방(세션과 달리 공유가 목적인 값)하나 **로그로 출력하지 않는다**.
- 수락 검증·멤버 등록은 반드시 서버(@Transactional)에서. 프론트 표시는 보조.
- 이미 멤버인 계정의 역할을 초대 수락이 덮어쓰지 않는다.
- 초대 관리(생성·목록·폐기)는 해당 프로젝트 ADMIN 전용 — 기존 인터셉터 규칙을 벗어나는 우회 경로를 만들지 않는다.

## 4. 테스트방법

`./gradlew test`(신규 InviteServiceTest 포함) → `npx tsc --noEmit` → `docker-compose up -d --build`(standalone CLI) 후 disabled 회귀 curl + 초대 CRUD curl(생성→미리보기→미로그인 accept 401→폐기) → 테스트 데이터 정리 → 커밋. SSO 실환경 합류 검증은 authentik 등록 후 수동.

---
**다음 페이즈:** `05-ws-mcp.md` (WebSocket·MCP 권한 정합).
