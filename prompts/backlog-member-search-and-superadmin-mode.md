# [백로그] 멤버 선택 검색 + 최고관리자 멤버십 현황 뷰

- **발견**: 2026-09-07, SSO 연동 검증 중 사용자 요청
- **상태**: ✅ 2026-09-07 배치 구현 완료 — 멤버 검색 필터·/api/admin/memberships·전체 멤버 현황 모달 적용
- **변경 이력**: 2026-09-07 "superAdmin 입장 모드 선택"은 사용자 결정으로 제외,
  대신 "프로젝트×멤버 전체 현황 뷰"로 대체

## 1. 멤버 관리 팝업 — 계정 검색

**문제**: 추가할 계정 선택 목록이 전체 계정을 그대로 나열함
(`PermissionService.assignableUsers()` = 로그인해본 전 계정). 사용자가 많아지면 고르기 어려움.

**개선**:
- 팝업 상단에 검색 입력 추가 — 이름(displayName)·아이디(username) 부분 일치 필터
- 클라이언트 필터로 충분 (계정 수백 명 수준까지는 목록 전체를 받아 프론트에서 거르면 됨).
  수천 명 규모가 되면 서버 검색 파라미터(`?q=`) 추가 검토
- MemoModal 의 "테이블 검색" 필터(`linkFilter`) 패턴과 동일한 UX 재사용

**위치**: 멤버 관리 팝업 컴포넌트(frontend), `GET /api/projects/{slug}/assignable-users`

## 2. 최고관리자 — 프로젝트×멤버 전체 현황 뷰 (신규)

**요구**: `erd-admins`(superAdmin) 계정일 때, **어떤 프로젝트에 어떤 멤버가
어떤 역할로 속해 있는지 한 화면에서** 볼 수 있어야 함.
현재는 superAdmin이라도 프로젝트를 하나씩 골라 멤버 관리 팝업을 열어야 해서 전체 파악이 어려움.

**구현 방향(안)**:
- Backend: `GET /api/admin/memberships` (superAdmin 전용 — `PermissionInterceptor`에서
  `principal.superAdmin()` 아니면 403)
  - 응답: `[{ project: {slug, name, roomCount}, members: [{userId, username, displayName, role}] }]`
  - 구현: `ProjectRepository.findAll` + `ProjectMemberRepository` 조인 조회 한 방
- Frontend: superAdmin일 때만 방 목록 헤더에 "전체 멤버 현황" 버튼 노출 → 모달/패널
  - 프로젝트별 그룹핑 테이블: 프로젝트명 · 멤버(이름, 역할 뱃지 ADMIN/EDITOR/VIEWER) · 방 수
  - 멤버 0명(관리 공백) 프로젝트, 특정 사용자가 속한 프로젝트 역추적이 쉽도록
    사용자명 필터 하나 추가 (1번의 검색 UX 재사용)
- 읽기 전용으로 시작 — 역할 변경/추가는 기존 프로젝트별 멤버 관리 팝업으로 이동하는 링크만 제공

**전제**: authentik `erd-admins` 그룹 생성(김동학님) 후 superAdmin 계정으로 검증

## 제외된 항목 (기록용)

- ~~superAdmin 입장 모드 선택(최고관리자/일반 사용자 진입 분기)~~ — 2026-09-07 사용자 결정으로 제외
