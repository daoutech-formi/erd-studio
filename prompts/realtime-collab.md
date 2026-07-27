# 실행 프롬프트: ERD Studio — React/TS + Spring Boot 재작성 및 실시간 팀 협업

> 이 문서는 AI 코딩 에이전트가 그대로 실행하는 프롬프트다.

## 1. 요구사항

**목적:** 기존 ERD 뷰어/편집기(Express + 순수 JS)를 **erd-studio**라는 이름의 React+TypeScript / Spring Boot+JPA 스택으로 전면 재작성하면서, 같은 팀 사용자들이 서로의 접속을 확인하고 동시 30명 기준 렌더링 지연 없이 스키마를 동시 편집·자동 저장할 수 있게 한다.

**동작 명세:**

- 입력: 브라우저(Chrome) 접속, 최초 접속 시 사용자 이름 1~20자 입력(이후 localStorage `erd_user` 키에서 자동 로드), 편집 조작(테이블/컬럼/관계 추가·수정·삭제, 노드 드래그)
- 처리:
  - 기존 기능 동등성: 도메인별 ERD 렌더링(시드 107테이블/122관계), 노드 클릭 상세 패널, 팬/줌, 검색, 편집 폼, JSON/SQL/DBML 내보내기를 새 스택에서 동일하게 제공
  - 모든 편집을 op(operation) 단위로 WebSocket 전송 → Spring Boot 서버가 PostgreSQL에 트랜잭션으로 즉시 반영 → 접속자 전원에게 브로드캐스트
  - 수신 클라이언트는 변경된 노드/엣지에 해당하는 React 컴포넌트만 리렌더 (전체 캔버스 리렌더 없음)
  - 노드 편집 폼을 연 사용자는 해당 테이블에 소프트 락 획득, 다른 사용자의 편집 폼 열기를 차단
- 출력:
  - 헤더에 접속자 목록(이니셜 원형 배지 + 사용자별 고유 색상)
  - 타인이 락 잡은 노드에 "{이름}님이 편집 중" 배지 + 그 사용자 색 테두리
  - 모든 편집이 별도 저장 버튼 없이 DB에 자동 저장됨 (기존 '☁ 서버에 저장' 버튼 제거)

**수용 기준:**

- [ ] 클린 기동 후 시드가 자동 적재되어 테이블 107개·관계 122개가 화면에 렌더링된다
- [ ] 노드 클릭 시 상세 패널(도메인·설명·컬럼 목록·참조 관계)이 표시되고, JSON/SQL/DBML 내보내기 버튼이 각각 파일을 다운로드한다 (기능 동등성)
- [ ] 브라우저 2개(시크릿 창 포함)로 접속 시, 각 창의 헤더 접속자 목록에 상대 이름이 3초 이내 표시된다
- [ ] 창 하나를 닫으면 나머지 창의 접속자 목록에서 해당 사용자가 10초 이내 제거된다
- [ ] A창에서 컬럼 수정 후 '적용' 클릭 → B창 화면에 500ms 이내 반영된다 (localhost 기준)
- [ ] A창의 편집 반영 시 B창의 노드 컴포넌트 리렌더 횟수 증가가 변경 대상 테이블 수 이하다 (개발 빌드의 렌더 카운터 `window.__nodeRenders`로 확인)
- [ ] 편집 후 페이지 전체 새로고침(F5) 시 편집 내용이 유지된다 (자동 저장 검증)
- [ ] A창에서 노드 편집 폼을 연 상태에서 B창이 같은 노드를 클릭하면 편집 폼 대신 "{A이름}님이 편집 중" 안내가 표시된다
- [ ] A창을 강제 종료(탭 닫기)하면 30초 이내 락이 해제되어 B창이 편집 폼을 열 수 있다
- [ ] 편집 모드에서 노드를 드래그해 옮긴 위치가 F5 후에도, 다른 접속자 화면에서도 동일하게 유지된다
- [ ] 변경 이력 화면에서 (사용자 이름, 시각, op 종류, 대상 테이블) 4개 항목이 행 단위로 조회되고, 특정 이력 시점으로 '복원' 클릭 시 스키마가 그 시점 상태로 되돌아간다
- [ ] Ctrl+Z로 자신의 직전 편집이 취소되고 Ctrl+Shift+Z로 재실행된다 (각 최소 10단계)
- [ ] 검색창에 컬럼명(예: `bill_no`)을 입력하면 그 컬럼을 가진 테이블이 강조된다
- [ ] 'PNG 내보내기' / 'SVG 내보내기' 버튼 클릭 시 현재 다이어그램 전체가 담긴 파일이 다운로드된다
- [ ] 시뮬레이션 스크립트로 WebSocket 클라이언트 30개 접속 + 초당 5 op 발생 상태에서, 편집 반영 지연 p95가 1초 이내를 유지한다
- [ ] `docker compose up -d --build` 만으로 전체 기능이 기동된다 (추가 수동 설정 없음)
- [ ] 외부 CDN/원격 리소스 요청 0건 (폐쇄망 동작 — 브라우저 Network 탭에서 localhost 외 요청 없음)
- [ ] `npx tsc --noEmit` 에러 0건, `./gradlew test` 전체 통과
- [ ] 직접 작성한 소스 파일(.ts/.tsx/.java)이 파일당 300줄 이하다 (자동 생성·테스트 파일 제외)

**범위 밖:**

- 로그인/SSO/권한 관리 (이름 입력만으로 식별, 누구나 편집 가능)
- 같은 노드의 동일 필드를 동시에 타이핑하는 문자 단위 병합(CRDT) — 소프트 락으로 대체
- 모바일/터치 대응
- 시드 데이터(schema.json) 내용 변경 (위치만 `backend/src/main/resources/seed/`로 이동)
- HTTPS/WSS 인증서 구성 (사내 HTTP 전제)
- 저장소 디렉토리명(`adcon-erd`) 변경 — 코드·이미지·패키지·UI 명칭만 erd-studio로 통일하고, 디렉토리 rename은 사용자가 수동으로 수행

## 2. 개발디테일

**프로젝트 명칭 규칙 (일괄 적용):**

- 프로젝트명: `erd-studio` / UI 타이틀: `ERD Studio`
- Java 패키지: `com.daou.erdstudio` / Gradle `rootProject.name = 'erd-studio-backend'`
- Docker 컨테이너·이미지: `erd-studio-db`, `erd-studio-backend`, `erd-studio-frontend`
- npm 패키지명: `erd-studio-frontend`
- DB 테이블명은 기존 `erd_domain`/`erd_table`/`erd_column`/`erd_relation` 유지 (기존 `erd-pgdata` 볼륨 데이터와 호환), `erd_history`만 신규

**기술 스택 (버전 고정):**

- 백엔드: Java 21, Spring Boot 3.3.x, Gradle 8.x, spring-boot-starter-web / spring-boot-starter-data-jpa / spring-boot-starter-websocket, PostgreSQL JDBC 드라이버. Lombok 금지 — DTO는 Java `record` 사용
- 프론트엔드: React 18, TypeScript 5(strict), Vite 5. 런타임 의존성은 `react`, `react-dom` 2개만
- DB: PostgreSQL 16 (기존 docker-compose의 db 서비스 그대로)

**디렉토리 구조 (전면 교체 — 기존 backend/frontend 소스는 git 첫 커밋에 보존 후 삭제):**

```
adcon-erd/                              # 디렉토리명은 유지 (범위 밖 참조)
├── docker-compose.yml                  # 서비스 3개 구조 유지, 컨테이너명만 erd-studio-*
├── .env.example
├── scripts/sim-load.mjs                # 부하 시뮬레이션 (Node 21+ 내장 WebSocket, 의존성 0)
├── backend/
│   ├── build.gradle  settings.gradle  Dockerfile        # multi-stage: gradle:8-jdk21 빌드 → eclipse-temurin:21-jre
│   └── src/main/
│       ├── resources/application.yml                    # ddl-auto: update, 환경변수로 DB 접속
│       ├── resources/seed/schema.json                   # 기존 backend/seed/schema.json 그대로 복사
│       └── java/com/daou/erdstudio/
│           ├── ErdStudioApplication.java
│           ├── config/WebSocketConfig.java              # /ws 핸들러 등록
│           ├── domain/          # @Entity: ErdDomain, ErdTable, ErdColumn, ErdRelation, ErdHistory
│           ├── repository/      # Spring Data JpaRepository 인터페이스 5개
│           ├── service/         # SchemaService(조회/전체교체/시드), OpService(op 검증·적용·이력), HistoryService(조회·복원)
│           ├── web/             # SchemaController, HistoryController + dto/ (record)
│           └── ws/              # ErdSocketHandler, SessionRegistry(presence), LockRegistry, dto/ (record)
└── frontend/
    ├── package.json  tsconfig.json  vite.config.ts  Dockerfile   # multi-stage: node:20-alpine 빌드 → nginx:alpine
    ├── nginx.conf                                    # /api 프록시 + /ws Upgrade 프록시 + 정적 서빙
    └── src/
        ├── main.tsx  App.tsx  types.ts               # types.ts: SchemaDoc, Op, WsMessage 등 공용 타입
        ├── api/http.ts  api/socket.ts                # fetch 래퍼 / WebSocket 클라이언트(재접속 포함)
        ├── state/schemaStore.ts  state/opReducer.ts  state/undo.ts   # useReducer+Context, op→상태 반영, undo/redo 스택
        ├── canvas/ErdCanvas.tsx  canvas/ErdNode.tsx  canvas/ErdEdge.tsx  canvas/layout.ts  canvas/usePanZoom.ts  canvas/useNodeDrag.ts
        ├── components/Header.tsx  PresenceBar.tsx  Toolbar.tsx  NameModal.tsx  DetailPanel.tsx  EditForm.tsx  HistoryPanel.tsx  SearchBox.tsx  Legend.tsx  Toast.tsx
        └── exporters/image.ts  sql.ts  dbml.ts  json.ts
```

**DB 스키마 (JPA 엔티티 매핑, 기존 테이블명·컬럼명 유지):**

- `ErdTable` 엔티티에 `posX`/`posY` (`pos_x`/`pos_y` DOUBLE PRECISION, NULL=자동 배치) 추가 — `ddl-auto: update`가 컬럼만 추가
- `ErdHistory` 신규: `id, userName, opKind, target, op(JSONB), snapshot(JSONB), createdAt` — JSONB는 `@JdbcTypeCode(SqlTypes.JSON)` 매핑. 이력 1,000행 초과 시 op 기록 트랜잭션 안에서 오래된 행부터 삭제. 복원은 선택 이력의 `snapshot`을 전체 교체 적용 후 그 복원 자체를 `history.restore` op로 기록
- 시드: 기동 시 `erd_table` count가 0이면 `resources/seed/schema.json`을 `SchemaService.replaceAll()`로 적재 (`ApplicationRunner`)

**WebSocket 프로토콜 (JSON, 봉투 `kind` 필드 — 경로 `/ws`, Spring `TextWebSocketHandler`):**

```
클라이언트 → 서버
{ kind: "hello", user: string, color: string }
{ kind: "op",    op: Op }
{ kind: "lock",  action: "acquire"|"release", table: string }
{ kind: "move",  table: string, x: number, y: number }        // 드래그 중 실시간, DB 저장 없음, 50ms throttle

서버 → 클라이언트
{ kind: "presence", users: [{ id, user, color }] }
{ kind: "op",       op: Op, seq: number }                      // DB 반영 성공한 op만, 발신자 포함 전원
{ kind: "locks",    locks: { [table]: { id, user, color } } }
{ kind: "move",     table, x, y, id }
{ kind: "error",    message: string }                          // 반영 실패 시 발신자에게만
```

**Op 타입 (프론트 `types.ts`와 백엔드 `ws/dto`에 동일 정의):**

```
Op = { type, user, payload }
type: "table.add"      payload: { name, domain, desc }
      "table.apply"    payload: { oldName, table: [name,domain,desc,hub?], columns: [[n,t,c,f?],...], relations: [[child,parent,label?],...] }
      "table.delete"   payload: { name }
      "table.move"     payload: { name, x, y }                 // 드래그 종료(mouseup) 시 1회
      "schema.replace" payload: { doc }                        // JSON 불러오기·이력 복원용
```

**서버 측 상태 (단일 backend 인스턴스 전제, 메모리 빈):**

- `SessionRegistry`: `ConcurrentHashMap<sessionId, {user, color}>` — presence 원천, 입장·퇴장 시 전원 브로드캐스트
- `LockRegistry`: `ConcurrentHashMap<tableName, sessionId>` — 세션 종료 시 그 세션의 락 전부 해제 후 재브로드캐스트
- heartbeat: 서버가 15초 간격 ping, 2회 연속 pong 미수신 시 세션 종료 (강제 종료 후 최대 30초 내 presence/락 정리)

**HTTP API:**

- `GET /api/health` — `{"ok":true}`
- `GET /api/schema` — 초기 로드용 `{domains, tables, relations, columns}` (tables 행은 `[name,domain,desc,hub,x,y]` 6요소)
- `PUT /api/schema` — JSON 불러오기용, 내부에서 `schema.replace` op로 이력 기록 + 브로드캐스트 (기존 `validateDoc` 검증 로직을 `OpService`에 Java로 이식)
- `GET /api/history?limit=50` — `[{id, userName, opKind, target, createdAt}]` (snapshot 제외)
- `POST /api/history/{id}/restore` — snapshot 복원 + 브로드캐스트

**프론트엔드 핵심 설계:**

- 상태: `schemaStore.ts`의 `useReducer` — 서버 에코 op만 상태에 반영 (발신자/수신자 단일 코드 경로). reducer는 변경된 테이블 엔트리만 새 객체로 교체해 `React.memo`된 `ErdNode`/`ErdEdge`가 변경분만 리렌더
- 팬/줌·드래그 중 이동은 React 상태를 거치지 않고 ref + `requestAnimationFrame`으로 `<g>` transform 직접 갱신 (mouseup 시에만 상태 커밋) — 60fps 유지
- 레이아웃: `layout.ts`가 `posX`/`posY` NULL인 노드에만 기존과 동일한 도메인 그리드 자동 배치 적용
- WebSocket: `socket.ts`가 끊기면 2초 간격 무한 재접속, 재접속 성공 시 `GET /api/schema` 전체 재동기화
- Undo/Redo: `undo.ts`가 자신이 보낸 op의 역연산을 스택에 push (`table.apply` inverse = 이전 값의 `table.apply`, `add`↔`delete`, `move` inverse = 이전 좌표), Ctrl+Z/Ctrl+Shift+Z에서 일반 op로 전송. 스택 깊이 최대 50
- 렌더 카운터: 개발 빌드(`import.meta.env.DEV`)에서만 `ErdNode` 렌더 시 `window.__nodeRenders` 증가
- 내보내기: SQL/DBML/JSON 생성 로직은 기존 `app.js:336-374`의 규칙(타입 변환 `uns`→`unsigned`, PK/UK 플래그 → pk/unique, FK 컬럼 추정)을 TypeScript로 동일하게 이식. PNG/SVG는 SVG 직렬화 + canvas(2x 배율)

**구현 순서 (각 단계 완료 시 git 커밋, 빌드 가능 상태 유지):**

1. `git init` + 기존 코드 전체를 첫 커밋으로 보존 → 기존 backend/frontend 소스 삭제 커밋
2. 백엔드 골격: Gradle + Spring Boot + JPA 엔티티/리포지토리 + 시드 적재 + `GET /api/health`·`GET /api/schema` (여기서 107/122 스모크 통과)
3. 프론트 골격: Vite+React+TS + 스키마 로드 + 캔버스 읽기 전용 렌더링(레이아웃·팬/줌·상세 패널·검색·범례)
4. 편집 기능: EditForm + op 정의 + `OpService` 적용/검증/이력 + WebSocket 왕복(에코 반영)
5. presence + 소프트 락 + heartbeat
6. 노드 드래그 + 위치 영속 + move 브로드캐스트
7. 이력 패널 + 복원, Undo/Redo
8. 내보내기 4종(JSON/SQL/DBML/PNG·SVG) + JSON 불러오기
9. Docker multi-stage 빌드 + nginx 프록시 + docker-compose 갱신 + `scripts/sim-load.mjs` + README 전면 갱신

**기존 코드 활용 (이식 원본):**

- `backend/src/server.js:22-37` `validateDoc` → `OpService`의 스키마 검증
- `frontend/public/js/app.js:22-51` 도메인 그리드 레이아웃 → `canvas/layout.ts`
- `frontend/public/js/app.js:336-374` SQL/DBML 내보내기 규칙 → `exporters/sql.ts`, `exporters/dbml.ts`
- `backend/seed/schema.json` → `backend/src/main/resources/seed/schema.json` (내용 무변경)

## 3. 제약사항

**하지 말 것:**

- `schema.json` 시드 내용 수정 금지 (위치 이동만 허용)
- `docker-compose.yml`의 서비스 3개(db/backend/frontend) 구조·볼륨명(`erd-pgdata`)·포트 변수(`WEB_PORT`) 변경 금지 — 컨테이너명·이미지명만 `erd-studio-*`로 변경
- 기존 DB 데이터를 파괴하는 마이그레이션 금지 — 기존 `erd_*` 4개 테이블의 기존 컬럼 삭제·타입 변경 금지 (`ddl-auto: update` 범위 내 추가만)
- 상태관리·UI·CSS 프레임워크 추가 금지 (Redux/zustand/MUI/tailwind 등 — useReducer+Context와 직접 작성한 CSS로 해결)
- Lombok 금지 (Java record와 명시적 생성자 사용)
- JSON/SQL/DBML 내보내기·불러오기 기능 제거 금지
- 외부 CDN·원격 폰트·원격 스크립트 참조 금지 (폐쇄망 동작 필수)

**지킬 규칙 (클린코드):**

- 직접 작성 소스 파일(.ts/.tsx/.java) **파일당 최대 300줄**, 함수·메서드 **최대 50줄** — 초과 시 파일/함수 분리 (자동 생성·테스트 파일 제외)
- React 컴포넌트는 파일당 1개, 파일명 = 컴포넌트명
- 백엔드 레이어 준수: Controller/Handler → Service → Repository. Controller와 WebSocket Handler에 비즈니스 로직 작성 금지 (검증·트랜잭션은 Service)
- TypeScript `strict: true`, `any` 사용 금지 (불가피하면 사유 주석)
- 공용 타입은 `frontend/src/types.ts` 단일 파일에 정의, 프로토콜 변경 시 백엔드 `ws/dto`와 함께 수정
- op 반영은 서버 DB 커밋 성공 후 브로드캐스트 (DB가 단일 진실 소스, 실패 시 발신자에게만 `error`)
- 발신자도 서버 에코를 받아 반영하는 단일 코드 경로 유지 (발신자/수신자 분기 금지)
- 주석·UI 문구·에러 메시지는 한국어
- 커밋 단위: 구현 순서 1~9단계 기준 (최소 9개, 각 커밋은 빌드 가능 상태)

**경계값:**

- 동시 WebSocket 접속 30개 + 초당 5 op에서 편집 반영 지연 p95 1초 이내
- `move`(드래그 중) 메시지 클라이언트 발신 throttle 50ms (초당 최대 20건/사용자)
- WebSocket 재접속 간격 2초 고정, heartbeat ping 15초 간격·타임아웃 2회
- 이력 보관 최대 1,000행, Undo/Redo 스택 각 최대 50
- WebSocket 메시지 최대 크기 1MB (Spring `setMaxTextMessageBufferSize`, 초과 시 `error` 회신)
- 사용자 이름 1~20자, 검증은 클라이언트+서버 양쪽
- 프론트 프로덕션 번들(gzip) 500KB 이하

**의존성:** 프론트 런타임 의존성 `react`·`react-dom` 2개만 (devDependencies: typescript, vite, @vitejs/plugin-react, @types/* 허용). 백엔드는 spring-boot-starter-web/data-jpa/websocket + PostgreSQL 드라이버 + spring-boot-starter-test만. Docker 베이스: `gradle:8-jdk21`/`eclipse-temurin:21-jre`/`node:20-alpine`/`nginx:alpine`/`postgres:16-alpine` 외 금지.

## 4. 테스트방법

**검증 명령:**

```bash
# 1) 백엔드 단위 테스트 (OpService 검증·적용, 이력 상한, 시드 적재 — 최소 10개 테스트)
cd backend && ./gradlew test

# 2) 프론트 타입·빌드 검사
cd frontend && npx tsc --noEmit && npm run build

# 3) 클린 기동
docker compose down -v && docker compose up -d --build
docker compose ps        # 3개 컨테이너 모두 Up

# 4) API 스모크
curl -sf http://localhost:8080/api/health                          # {"ok":true}
curl -sf http://localhost:8080/api/schema | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['tables']), len(d['relations']))"
# 출력: 107 122
curl -sf "http://localhost:8080/api/history?limit=5"               # HTTP 200 + JSON 배열

# 5) 부하 시뮬레이션 (Node 21+ 내장 WebSocket, 호스트에서 실행)
node scripts/sim-load.mjs --url ws://localhost:8080/ws --clients 30 --ops-per-sec 5 --duration 60
# 각 op 발신→수신 왕복 지연 측정, p95 ≤ 1000ms 이고 수신 누락 0건이면 exit 0

# 6) 클린코드 검사 — 출력이 비어 있어야 통과
find backend/src/main frontend/src -name '*.java' -o -name '*.ts' -o -name '*.tsx' | xargs wc -l | awk '$2!="total" && $1>300'
```

**통과 기준:**

- 위 1~5가 주석의 출력·종료 코드와 일치, 6의 출력 0줄
- `git log --oneline` 커밋 9개 이상

**엣지케이스:**

- [ ] op 반영 실패(예: 존재하지 않는 도메인으로 `table.apply`) 시 발신자 화면에 에러 토스트가 뜨고, 다른 접속자 화면과 DB는 변하지 않는다
- [ ] `docker compose restart backend` 후 열린 브라우저들이 10초 이내 자동 재접속하고 편집이 다시 동작한다
- [ ] 같은 이름으로 두 명이 접속해도 presence에 2건이 별도 표시된다 (세션 id 기준)
- [ ] 락을 잡은 채 탭을 강제 종료하면 30초 이내 다른 사용자가 그 노드의 편집 폼을 열 수 있다
- [ ] 이력 1,000행 초과 상태에서 새 op 기록 시 총 행 수가 1,000 이하로 유지된다 (`SELECT COUNT(*) FROM erd_history`)
- [ ] 위치 저장 노드(pos_x NOT NULL)와 미저장 노드(NULL)가 섞여도 렌더링 오류가 없다
- [ ] `schema.replace`(JSON 불러오기) 직후 다른 접속자 화면도 새 스키마로 갱신된다
- [ ] Undo 스택이 빈 상태의 Ctrl+Z는 오류 없이 무시된다 (no-op)
- [ ] 구버전 4요소 tables 행(`[name,domain,desc,hub]`) JSON을 불러와도 x,y 없이 정상 동작한다

**수동 확인 (브라우저 2창 — 일반 + 시크릿):**

1. 두 창 접속 → 각각 이름 입력 → 양쪽 헤더에 상대 배지가 3초 이내 표시
2. A창 편집 모드 → 노드 클릭(락 획득) → B창에서 같은 노드 클릭 시 "○○님이 편집 중" 표시
3. A창에서 컬럼 추가 후 '적용' → B창에 500ms 이내 반영, B창 콘솔에서 `window.__nodeRenders` 증가량이 변경 테이블 수 이하인지 확인
4. A창에서 노드 드래그 → B창 실시간 이동 확인 → A창 F5 → 위치 유지
5. 이력 패널에서 방금 편집이 (이름·시각·op·대상) 행으로 조회 → 이전 시점 '복원' → 두 창 모두 되돌아감
6. 검색창에 `bill_no` 입력 → 해당 컬럼 보유 테이블 강조
7. JSON/SQL/DBML/PNG/SVG 내보내기 각각 다운로드 및 열람 확인
8. 브라우저 Network 탭에서 localhost 외부 요청 0건, 탭 타이틀이 "ERD Studio"인지 확인
