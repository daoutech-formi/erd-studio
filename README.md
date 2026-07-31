# ERD Studio

팀이 **동시에 편집하는** DB 테이블 관계도(ERD) 뷰어/편집기.
React + TypeScript 프론트엔드, Spring Boot + JPA 백엔드, PostgreSQL 구조이며
WebSocket으로 접속자 표시(presence)·소프트 락·실시간 동기화를 제공합니다.
배포는 단일 **app** 컨테이너(Spring Boot가 정적 프론트 + REST + WebSocket을 같은 오리진에서 서빙)와
**db**(PostgreSQL) 2컨테이너 구성이며, Coolify 헬스체크는 `/api/health`를 사용합니다.
시드 데이터는 애드콘 쿠폰판매 서비스 스키마(107개 테이블, 122개 관계)입니다.

## 구조

```
├── Dockerfile                    # 프론트(Vite) 정적 산출물 + 백엔드(jar) 단일 app 이미지
├── docker-compose.yml            # app + db (2컨테이너)
├── .env.example                  # 환경변수 템플릿 (실제 값은 Coolify 주입)
├── scripts/sim-load.mjs          # 부하 시뮬레이션 (Node 21+, 의존성 없음)
├── backend/                      # Spring Boot 3.3 · Java 21 · Gradle
│   └── src/main/
│       ├── resources/seed/schema.json      # 최초 기동 시 적재되는 시드
│       └── java/com/daou/erdstudio/
│           ├── domain/ repository/         # JPA 엔티티(erd_* 테이블) · 리포지토리
│           ├── service/                    # SchemaService · OpService · History*
│           ├── web/                        # REST 컨트롤러 + DTO(record)
│           └── ws/                         # /ws 핸들러 · presence · 소프트 락
└── frontend/                     # React 18 · TypeScript · Vite
    └── src/
        ├── api/                  # HTTP 클라이언트 · WebSocket(재접속 포함)
        ├── state/                # useReducer 스토어 · op 반영 · Undo/Redo
        ├── canvas/               # SVG 캔버스 · 레이아웃 · 팬줌 · 노드 드래그
        ├── components/           # 헤더 · 접속자 · 편집 폼 · 이력 패널 등
        └── exporters/            # JSON · SQL · DBML · PNG/SVG
```

## 실행

```bash
docker compose up -d --build     # (구버전 CLI는 docker-compose)
# 브라우저에서 http://localhost:8080 접속
```

- 최초 기동 시 방이 하나도 없으면 기본 방(`애드콘 ERD`)을 만들고 시드를 적재합니다.
  데이터는 `erd-pgdata` 볼륨에 영속됩니다.
- 기존 DB를 업그레이드하는 경우: `erd_*` 테이블에 `room_id`(NOT NULL)가 추가되고 `erd_domain`의 PK가
  바뀌었으므로, `docker compose down -v`로 초기화하거나 `room_id`를 수동 백필해야 합니다.
- 포트 변경: `.env.example`을 `.env`로 복사한 뒤 `WEB_PORT` 수정 (컨테이너 내부는 항상 3000).
- **Coolify 배포**: 저장소를 연결하면 루트 `docker-compose.yml`을 사용합니다. `POSTGRES_*` 값은
  Coolify 환경변수로 주입하고(`.env`는 커밋하지 않음), 헬스체크 경로는 `/api/health`로 지정하세요.

## 사용법

- **접속**: 최초 1회 이름 입력(1~20자) → 방 목록에서 방을 고르면 그 방의 ERD가 열립니다.
- **방**: 서버 전체 최대 20개, 한 방에 동시 접속 최대 10명(같은 브라우저의 여러 탭은 1명).
  방마다 테이블·관계·도메인·이력·락이 완전히 분리되며, 헤더의 `← 방 목록`으로 나갈 수 있습니다.
- **보기**: 노드 클릭 → 관계 강조 + 컬럼 상세. 드래그 이동, 휠 확대/축소, 테이블명·컬럼명 검색.
- **편집**: `✏ 편집 모드` → 노드 클릭 시 편집 폼. **저장 버튼이 없습니다** — 적용 즉시 모든
  접속자에게 반영되고 PostgreSQL에 자동 저장됩니다.
- **동시 편집 보호**: 편집 폼을 연 테이블은 다른 사람에게 "○○님이 편집 중"으로 잠깁니다.
  접속이 끊기면 30초 이내 자동 해제됩니다.
- **배치**: 편집 모드에서 노드를 드래그하면 위치가 저장되어 모두에게 동일하게 보입니다.
- **이력**: `🕘 변경 이력` → 누가 언제 무엇을 바꿨는지 조회, 원하는 시점으로 복원.
- **Undo/Redo**: Ctrl+Z / Ctrl+Shift+Z (자신의 편집만, 최대 50단계).
- **내보내기**: JSON(백업) / SQL(MariaDB) / DBML(dbdiagram.io) / PNG·SVG(이미지).

## API·프로토콜

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/health` | 헬스체크 |
| GET | `/api/rooms` | 방 목록 (`[{id, name, createdBy, createdAt, tableCount, userCount}]`) |
| POST | `/api/rooms` | 방 생성 (`{name}` + `X-User` 헤더, 최대 20개) |
| DELETE | `/api/rooms/{roomId}` | 방과 그 방의 모든 데이터 삭제 |
| GET | `/api/rooms/{roomId}/schema` | 그 방의 전체 스키마 (`tables` 행: `[name, domain, desc, hub, x, y]`) |
| PUT | `/api/rooms/{roomId}/schema` | 전체 교체 (JSON 불러오기용, `X-User` 헤더) |
| GET | `/api/rooms/{roomId}/history?limit=50` | 변경 이력 목록 |
| POST | `/api/rooms/{roomId}/history/{id}/restore` | 해당 시점 스냅샷으로 복원 |
| POST | `/api/rooms/{roomId}/ddl/preview` · `/ddl/import` | DDL 임포트 미리보기 · 적용 |
| WS | `/ws` | `hello` / `op` / `lock` / `move` 송신 · `presence` / `op` / `locks` / `move` / `error` 수신 |

`hello`는 `{kind:"hello", roomId, clientKey, user, color}` 형식이며, 이 메시지로 방에 입장한 뒤에만
`op`/`lock`/`move`가 처리됩니다. `clientKey`는 브라우저마다 localStorage에 보관하는 식별자로,
같은 브라우저의 여러 탭은 정원·접속자 목록에서 1명으로 집계됩니다. 방 정원(10명)을 넘으면
`{kind:"error", fatal:true}`를 보내고 연결을 끊습니다.

편집은 op 단위(`table.add` / `table.apply` / `table.delete` / `table.move` / `schema.replace`)로
서버가 DB에 반영한 뒤 **같은 방의** 전원에게 브로드캐스트합니다. 이력은 방마다 최대 1,000행 보관됩니다.

## 개발

```bash
# 백엔드 (PostgreSQL 필요 — 예: docker run -p 5432:5432 -e POSTGRES_USER=erd -e POSTGRES_PASSWORD=erd -e POSTGRES_DB=erd_studio postgres:16-alpine)
cd backend && ./gradlew bootRun     # http://localhost:3000
cd backend && ./gradlew test        # 단위 테스트

# 프론트엔드 (백엔드로 /api·/ws 프록시)
cd frontend && npm install && npm run dev    # http://localhost:5173
cd frontend && npx tsc --noEmit             # 타입 검사
```

## 부하 시뮬레이션

```bash
node scripts/sim-load.mjs --url ws://localhost:8080/ws --clients 30 --ops-per-sec 5 --duration 60
# p95 지연 ≤ 1000ms, 수신 누락 0건이면 exit 0
```

## 폐쇄망 배포

```bash
# 외부망에서
docker compose build
docker pull postgres:16-alpine
docker save -o erd-studio-images.tar erd-studio-app postgres:16-alpine

# 폐쇄망에서
docker load -i erd-studio-images.tar
docker compose up -d   # build 없이 로드된 이미지로 기동
```

## 데이터 초기화

```bash
docker compose down -v && docker compose up -d   # 시드부터 다시 시작
```

## MCP 연결

백엔드가 MCP 서버(SSE)를 함께 노출한다. Claude Code에서 연결하면 **구독 라이선스의 Claude가
DDL 의미 분석·도메인 분류를 수행**하고, 도구 호출로 방에 직접 반영할 수 있다 (API 키 불필요).
반영 결과는 접속 중인 브라우저 전원에게 WebSocket으로 실시간 전파된다.

```bash
# 등록 (도메인은 배포 주소로 교체)
claude mcp add --transport sse erd-studio http://<erd-studio 도메인>/sse

# 연결 확인
claude mcp list

# 연결 해제 (삭제) — 서버의 ERD 데이터에는 영향 없음
claude mcp remove erd-studio
```

`ERD_MCP_TOKEN` 환경변수를 설정하면 등록 시
`--header "Authorization: Bearer <토큰>"` 을 붙여야 한다(선택, 미설정 시 사내망 개방).

> Claude Desktop(Cowork)의 원격 커넥터는 Anthropic 클라우드를 경유하므로 폐쇄망 LAN 서버에는
> 연결되지 않는다 — **Claude Code CLI**를 사용한다. (CLI는 로컬 머신에서 서버로 직접 접속)

### 제공 tool

| tool | 설명 |
|---|---|
| `list_rooms` | ERD 방 목록 조회 (id·이름·테이블 수) |
| `create_room` | 방 생성 (전체 최대 20개) |
| `get_schema` | 방의 전체 스키마 문서 조회 |
| `replace_schema` | 스키마 문서 전체 교체 — 도메인 재구성·테이블 재배치용 |
| `preview_ddl` | DDL 파싱 후 변경 요약 미리보기 (반영 없음) |
| `import_ddl` | DDL 을 방에 적용 (merge/replace) |

### 사용 예 (Claude Code 대화)

```
> 이 DDL 파일을 '애드콘 ERD' 방에 임포트하고,
  도메인은 테이블 의미를 분석해서 다시 분류해줘. @create-tables.sql
```

Claude가 `list_rooms` → `import_ddl` → `get_schema` → (의미 분류) → `replace_schema`
순으로 도구를 호출해 분류까지 마친다.
