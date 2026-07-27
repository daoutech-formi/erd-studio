# 애드콘(ADCON) ERD 뷰어/편집기

애드콘 쿠폰판매 서비스의 테이블 관계도(107개 테이블, 122개 관계)를 시각화하고 편집하는 웹 앱.
단일 HTML 파일을 프론트엔드(nginx) / 백엔드(Node.js + Express) / DB(PostgreSQL) 3계층으로 분리한 구조입니다.

## 구조

```
adcon-erd/
├── docker-compose.yml        # db + backend + frontend 오케스트레이션
├── .env.example              # 환경변수 템플릿
├── backend/
│   ├── Dockerfile
│   ├── package.json          # 의존성: express, pg 두 개뿐
│   ├── seed/schema.json      # 최초 기동 시 DB에 적재되는 시드 데이터
│   └── src/
│       ├── server.js         # API 서버 (GET/PUT /api/schema)
│       └── db.js             # PostgreSQL 스키마 생성/저장/조회
└── frontend/
    ├── Dockerfile            # nginx 정적 서빙 + /api 리버스 프록시
    ├── nginx.conf
    └── public/
        ├── index.html
        ├── css/style.css
        └── js/app.js         # ERD 렌더링/편집 로직 (외부 라이브러리 없음)
```

## 실행

```bash
cd adcon-erd
docker compose up -d --build
# 브라우저에서 http://localhost:8080 접속
```

- 최초 기동 시 백엔드가 `backend/seed/schema.json`을 PostgreSQL에 자동 적재합니다 (DB가 비어있을 때만).
- 이후에는 DB에 저장된 데이터가 항상 우선입니다. 데이터는 `erd-pgdata` 볼륨에 영속됩니다.
- 포트 변경: `.env.example`을 `.env`로 복사한 뒤 `WEB_PORT` 수정.

## 사용법

- **보기**: 노드 클릭 → 관계 강조 + 컬럼 상세 패널. 드래그 이동, 휠 확대/축소, 테이블명 검색.
- **편집**: 우측 상단 `✏ 편집 모드` → 노드 클릭 시 편집 폼. 테이블 추가/삭제, 컬럼/관계 수정.
- **저장**: 편집 후 `☁ 서버에 저장` 버튼 → PostgreSQL에 전체 스키마가 트랜잭션으로 반영됩니다.
- **내보내기**: JSON(백업/공유용) / SQL(MariaDB CREATE TABLE) / DBML(dbdiagram.io, ERD Cloud import용).

## API

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/health` | 헬스체크 |
| GET | `/api/schema` | 전체 스키마 조회 (`{domains, tables, relations, columns}`) |
| PUT | `/api/schema` | 전체 스키마 교체 저장 (트랜잭션, 유효성 검증 포함) |

## DB 스키마 (PostgreSQL)

- `erd_domain` — 도메인 (key, name, color, sort_order)
- `erd_table` — 테이블 (name, domain_key, description, is_hub, sort_order)
- `erd_column` — 컬럼 (table_id FK, name, col_type, comment, flag, sort_order)
- `erd_relation` — 관계 (child_table_id FK, parent_table_id FK, label)

테이블 삭제 시 컬럼/관계는 `ON DELETE CASCADE`로 함께 정리됩니다.

## 폐쇄망 배포

인터넷이 되는 PC에서 이미지를 빌드해 tar로 옮기면 됩니다.

```bash
# 외부망에서
docker compose build
docker pull postgres:16-alpine
docker save -o adcon-erd-images.tar adcon-erd-frontend adcon-erd-backend postgres:16-alpine

# 폐쇄망에서
docker load -i adcon-erd-images.tar
docker compose up -d   # build 없이 로드된 이미지로 기동
```

## 데이터 초기화

시드 데이터부터 다시 시작하려면 볼륨을 지우고 재기동합니다.

```bash
docker compose down -v && docker compose up -d
```
