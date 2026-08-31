# 실행 프롬프트 — Phase 2: 프로젝트 도입 + 방 소속화

> 이 문서는 AI 코딩 에이전트가 그대로 실행하는 프롬프트다. 대상 저장소: `/Users/formi/adcon-erd`.
> 선행: Phase 1(SSO 로그인 토대, `com.daou.erdstudio.auth`) 머지 완료. 전체 설계는 `00-roadmap.md`.
> 참고 원본: `/Users/formi/cortex` 의 `project/ProjectContext.java`, `project/ProjectInterceptor.java`.

## 1. 요구사항

**목적:** ERD 방(erd_room)의 상위 묶음인 **프로젝트**를 도입한다. 방은 반드시 하나의 프로젝트에 속하고, 방 목록·생성은 "현재 선택한 프로젝트" 범위로 좁혀진다. 아직 권한·가시성 제한은 없다(전원이 모든 프로젝트를 본다 — Phase 3에서 멤버십으로 제한). 기존 방은 전부 `legacy` 프로젝트로 백필한다.

**동작 명세:**

- 입력 1 — 프로젝트 목록/생성: `GET /api/projects`, `POST /api/projects` 본문 `{"name":"애드웰"}`.
- 입력 2 — 프로젝트 범위 요청: 모든 `/api/**` 요청의 `X-Project-Id: {slug}` 헤더. 프론트는 선택한 프로젝트 slug 를 localStorage(`erd_project`)에 두고 모든 API 호출에 싣는다.
- 입력 3 — 방 목록/생성: 기존 `GET/POST /api/rooms` — 이제 현재 프로젝트 범위로 동작.
- 입력 4 — 딥링크: `GET /api/rooms/{roomId}` — 방 하나를 프로젝트 slug 와 함께 조회(공유 링크가 프로젝트를 넘나들 수 있도록).

- 처리:
  1. `ProjectInterceptor`가 `X-Project-Id` 헤더(slug)를 읽어 요청 스레드의 `ProjectContext`(ThreadLocal)에 프로젝트 id 를 넣는다. **헤더가 없거나 slug 가 미지(스테일)면 컨텍스트를 비워 두고, 서비스가 `legacy` 프로젝트로 폴백**한다(구 클라이언트·새로고침 직후에도 동작. Phase 3에서 권한과 함께 엄격화).
  2. `ProjectBootstrap`(ApplicationRunner, 멱등)이 기동 시: ① `legacy`/「레거시」 프로젝트가 없으면 생성 ② `erd_room.project_id IS NULL` 인 방을 전부 legacy 로 백필 ③ 구버전의 **erd_room(name) 전역 유니크 제약을 발견하면 드랍**(이름 유니크는 프로젝트 내로 좁힘. PostgreSQL 전용 — 실패는 무시하고 경고 로그).
  3. 방 생성 시 현재 프로젝트에 소속시키고, 이름 중복·방 개수 상한(20)은 **프로젝트 단위**로 검사한다.
  4. 프로젝트 slug 는 서버가 자동 생성한다(`p` + 랜덤 base36 6자, 충돌 시 재시도). 이름은 1~50자, 중복 불가.
  5. 프로젝트 삭제는 **빈 프로젝트만** 허용하고 `legacy` 는 삭제 불가.

- 출력:
  - `GET /api/projects`: `200` `[{"id":1,"slug":"legacy","name":"레거시","roomCount":3}, ...]` (id 순).
  - `POST /api/projects`: `200` 생성된 프로젝트 행. 이름 중복/빈 이름은 `400` `{"error":"..."}`.
  - `DELETE /api/projects/{slug}`: 빈 프로젝트면 `200` `{"ok":true}`, 방이 있으면 `400`, legacy 는 `400`.
  - `GET /api/rooms` (+`X-Project-Id`): 해당 프로젝트의 방만. 헤더 없으면 legacy 의 방(=기존 응답과 동일).
  - `GET /api/rooms/{roomId}`: `200` RoomInfo(+`projectSlug`), 없으면 `400`.
  - 방 목록/단건 RoomInfo 에 `projectSlug` 필드 추가(기존 필드는 그대로 — 프론트 하위호환).

**수용 기준:**

- [ ] 기동 후 `project` 테이블이 있고 `slug='legacy'` 행이 정확히 1개다(2회 재기동에도 1개 — 멱등).
- [ ] 기존 방 전부 `erd_room.project_id` = legacy id 로 백필됐다(`SELECT count(*) FROM erd_room WHERE project_id IS NULL` = 0).
- [ ] `curl -s localhost:8080/api/projects` 에 legacy 가 있고 roomCount 가 실제 방 수와 같다.
- [ ] `curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"애드웰"}' localhost:8080/api/projects` 가 slug 를 돌려주고, 같은 이름 재요청은 `400`.
- [ ] 새 프로젝트 slug 로 `GET /api/rooms` 하면 `[]`, 헤더 없이 하면 기존 방 목록 그대로.
- [ ] 새 프로젝트 slug 로 방을 만들면 그 프로젝트 목록에만 보이고 legacy 목록엔 없다.
- [ ] **같은 방 이름을 서로 다른 두 프로젝트에 만들 수 있다**(전역 유니크 제약 드랍 확인). 같은 프로젝트 안에서는 `400`.
- [ ] `GET /api/rooms/{id}` 응답에 올바른 `projectSlug` 가 있다.
- [ ] 방 있는 프로젝트 `DELETE` 는 `400`, 빈 프로젝트는 `200` 후 목록에서 사라짐, legacy 삭제는 `400`.
- [ ] 프론트: 방 목록 화면에 프로젝트 선택 드롭다운 + ＋ 새 프로젝트. 프로젝트를 바꾸면 방 목록이 그 프로젝트 것으로 갈리고, 선택은 새로고침에도 유지(localStorage).
- [ ] 다른 프로젝트 방의 딥링크(`#/room/:id`)로 들어가면 그 방의 프로젝트로 자동 전환돼 입장한다.
- [ ] 기존 백엔드 테스트 통과(기존부터 실패하던 도메인 분류 6건 제외), `npx tsc --noEmit` 통과.

**범위 밖:** 멤버십·가시성 제한·권한 검사(Phase 3), 초대(Phase 4), WS/MCP 프로젝트 검사(Phase 5), 프로젝트 이름 변경 UI.

## 2. 개발디테일

신규 — `backend/src/main/java/com/daou/erdstudio/project/`:
- `Project.java` — `@Entity @Table(name="project")`, BaseTimeEntity 상속. `slug`(unique, 32), `name`(unique, 50).
- `ProjectRepository.java` — `Optional<Project> findBySlug(String)`, `boolean existsBySlug(String)`, `boolean existsByName(String)`, `List<Project> findAllByOrderByIdAsc()`.
- `ProjectContext.java` — cortex 원본 그대로(ThreadLocal<Long>, set/getProjectId/clear/runWith).
- `ProjectInterceptor.java` — cortex 원본에서 **미지 slug 를 예외 대신 무시(폴백)** 로 변경. afterCompletion 에서 clear.
- `ProjectService.java` — legacy 보장·현재 프로젝트 해석(`currentProjectId()`: 컨텍스트 값 or legacy id)·목록·생성(slug 생성 포함)·빈 프로젝트 삭제.
- `ProjectBootstrap.java` — ApplicationRunner. legacy 생성 → 백필 UPDATE → 전역 유니크 드랍(네이티브, try/catch로 H2/재실행 무해).
- `ProjectController.java` — GET/POST `/api/projects`, DELETE `/api/projects/{slug}`.

변경 — 백엔드:
- `domain/ErdRoom.java` — `project_id`(Long, nullable 컬럼) 추가 + 생성자 확장 + 백필용 `assignProject(Long)`.
- `repository/ErdRoomRepository.java` — `findByProjectIdOrderByIdAsc`, `existsByProjectIdAndName`, `countByProjectId`, 백필용 `findByProjectIdIsNull`.
- `service/RoomService.java` — list/create 를 프로젝트 범위로(이름 중복·상한 20 을 프로젝트 단위 검사).
- `web/RoomController.java` — `ProjectService.currentProjectId()` 사용, `RoomInfo`에 `projectSlug` 추가, `GET /{roomId}` 단건 조회 추가.
- `config/WebConfig.java` — ProjectInterceptor 를 order 1 로 `/api/**` 등록(`/api/auth/**` 제외).

변경 — 프론트엔드:
- `api/http.ts` — `Project` 타입, `fetchProjects/createProject/deleteProject/fetchRoom`, localStorage `erd_project` + 모든 요청에 `X-Project-Id` 를 싣는 내부 fetch 래퍼, `RoomInfo.projectSlug`.
- `components/RoomList.tsx` — 프로젝트 드롭다운·＋ 새 프로젝트(빈 프로젝트 🗑 삭제 포함), 프로젝트 전환 시 방 목록 재조회.
- `App.tsx` — 딥링크를 `fetchRoom(id)` 로 바꾸고 `projectSlug` 로 선택 프로젝트를 맞춘 뒤 입장.

## 3. 제약사항

- 기존 API 경로·응답 필드를 깨지 말 것(RoomInfo 는 필드 추가만). 헤더 없는 요청은 legacy 폴백으로 **기존 클라이언트와 동일 동작**.
- `ddl-auto: update` 전제: 기존 테이블에 NOT NULL 컬럼 추가 불가 → `project_id` 는 nullable 컬럼 + 부트스트랩 백필로 처리하고, 논리적으로는 서비스가 항상 채운다.
- 부트스트랩·제약 드랍은 몇 번을 재실행해도 안전(멱등)해야 하며 H2(테스트)에서 조용히 통과해야 한다.
- 신규 의존성 금지. Lombok 금지(기존 스타일 유지).
- WebSocket(`/ws`)·MCP(`/sse`,`/mcp`)는 이번에도 손대지 않는다(roomId 로 이미 스코프됨).

## 4. 테스트방법

1. `cd backend && ./gradlew test` — 기존 통과 테스트 유지 + 신규 `ProjectServiceTest`(legacy 멱등·범위 분리·이름 규칙·빈 프로젝트 삭제) 통과.
2. `cd frontend && npx tsc --noEmit` 통과.
3. `docker-compose up -d --build` 후 수용 기준의 curl·psql 시나리오 실행.
4. 브라우저: 프로젝트 만들기 → 전환 → 방 만들기 → 새로고침 유지 → 타 프로젝트 방 딥링크 자동 전환 확인.

---
**다음 페이즈:** `03-membership.md` (멤버십·권한·가시성).
