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

- 최초 기동 시 DB가 비어 있으면 시드를 자동 적재합니다. 데이터는 `erd-pgdata` 볼륨에 영속됩니다.
- 포트 변경: `.env.example`을 `.env`로 복사한 뒤 `WEB_PORT` 수정 (컨테이너 내부는 항상 3000).
- **Coolify 배포**: 저장소를 연결하면 루트 `docker-compose.yml`을 사용합니다. `POSTGRES_*` 값은
  Coolify 환경변수로 주입하고(`.env`는 커밋하지 않음), 헬스체크 경로는 `/api/health`로 지정하세요.

## 사용법

- **접속**: 최초 1회 이름 입력(1~20자) → 헤더에 접속자 배지가 실시간 표시됩니다.
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
| GET | `/api/schema` | 전체 스키마 (`tables` 행: `[name, domain, desc, hub, x, y]`) |
| PUT | `/api/schema` | 전체 교체 (JSON 불러오기용, `X-User` 헤더) |
| GET | `/api/history?limit=50` | 변경 이력 목록 |
| POST | `/api/history/{id}/restore` | 해당 시점 스냅샷으로 복원 |
| WS | `/ws` | `hello` / `op` / `lock` / `move` 송신 · `presence` / `op` / `locks` / `move` / `error` 수신 |

편집은 op 단위(`table.add` / `table.apply` / `table.delete` / `table.move` / `schema.replace`)로
서버가 DB에 반영한 뒤 전원에게 브로드캐스트합니다. 이력은 최대 1,000행 보관됩니다.

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
