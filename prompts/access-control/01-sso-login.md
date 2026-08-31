# 실행 프롬프트 — Phase 1: SSO 로그인 토대

> 이 문서는 AI 코딩 에이전트가 그대로 실행하는 프롬프트다. 대상 저장소: `/Users/formi/adcon-erd`.
> 참고 원본: `/Users/formi/cortex` 의 `auth` 패키지(거의 그대로 이식). 전체 설계는 `prompts/access-control/00-roadmap.md`.

## 1. 요구사항

**목적:** ERD Studio에 authentik OIDC SSO 로그인을 도입한다. 이 페이즈는 **로그인/세션/로그아웃과 "내가 누구인지" 확정까지만** 한다. 아직 어떤 기능도 권한으로 막지 않는다(프로젝트·멤버십은 Phase 2·3). `ERD_OIDC_ENABLED=false`(기본)이면 앱은 지금과 100% 동일하게 동작해야 한다.

**동작 명세:**

- 입력 1 — 로그인 시작: 브라우저가 `GET /api/auth/oidc/login` 진입. → authentik authorize URL로 이동(state+PKCE는 `erd_oidc` HttpOnly 쿠키로 왕복).
- 입력 2 — 콜백: `GET /api/auth/oidc/callback?code=&state=` → 코드교환 → userinfo → 계정 생성/연결 → `erd_session` 세션 쿠키 발급 후 `/` 로 이동.
- 입력 3 — 상태 조회: `GET /api/auth/me` (쿠키 유무에 따라 로그인/게스트).
- 입력 4 — 로그아웃: `POST /api/auth/logout` → 세션 삭제 + 쿠키 만료.

- 처리:
  1. `AuthInterceptor`가 모든 `/api/**` 요청에서 `erd_session` 쿠키를 `auth_session` 테이블로 조회해 요청 주체를 `USER(id)` 또는 `GUEST`로 확정하고 `AuthContext`(ThreadLocal)에 넣는다.
  2. OIDC userinfo의 `sub`로 `app_user`를 찾고(없으면 `preferred_username`으로 매칭·연결, 그래도 없으면 신규 생성), 매 로그인마다 표시이름·admin그룹 여부를 동기화한다.
  3. userinfo의 `groups`에 `erd.oidc.admin-group` 값이 있으면 `super_admin=true`로 동기화한다.
  4. `erd.oidc.enabled=false`면 `/api/auth/oidc/login`·`/callback`은 `503`을 반환하고, 전 요청은 게스트로 해석된다(기존 동작 유지).

- 출력:
  - `GET /api/auth/oidc/login` (enabled): `200` HTML(즉시 authorize로 JS 이동) + `Set-Cookie: erd_oidc=...; HttpOnly; Path=/api/auth/oidc; SameSite=Lax; Max-Age=300`.
  - 콜백 성공: `200` HTML(`/` 로 이동) + `Set-Cookie: erd_session={토큰}; HttpOnly; Path=/; SameSite=Lax; Max-Age=2592000`.
  - 콜백 실패: `302 /?login_error={state|exchange|profile|username|conflict}`.
  - `GET /api/auth/me`: 비로그인 `200` `{"authenticated":false,"superAdmin":false}`; 로그인 `200` `{"authenticated":true,"userId":..,"username":"..","displayName":"..","superAdmin":..}`.
  - `POST /api/auth/logout`: `200` + `Set-Cookie: erd_session=; Max-Age=0`.
  - `login`/`callback` (enabled=false): `503`.

**수용 기준:**

- [ ] `ERD_OIDC_ENABLED=false`(기본)로 기동 시: `curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/auth/oidc/login` 가 `503`.
- [ ] `enabled=false`에서 `curl -s localhost:8080/api/auth/me` 의 `authenticated` 가 `false`.
- [ ] `enabled=false`에서 기존 API가 종전대로: `curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/rooms` 가 `200`, 방 생성/삭제도 종전대로 동작(회귀 없음).
- [ ] `curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/health` 가 `200`(인증 인터셉터가 헬스·정적 리소스를 막지 않음).
- [ ] 기동 후 `app_user`, `auth_session` 테이블이 생성돼 있다(`\dt`).
- [ ] 단위 테스트: PKCE `code_challenge` = base64url(SHA-256(verifier)) 검증, state 불일치 시 `OidcLoginException("state")`, `enabled=false`일 때 login이 503 — 이 3개가 통과한다.
- [ ] (수동, authentik 등록 후) `ERD_OIDC_ENABLED=true`로 브라우저에서 `/api/auth/oidc/login` → authentik 로그인 → `/`로 복귀 후 `GET /api/auth/me`의 `authenticated`가 `true`, `displayName`이 내 이름. 로그아웃 후 다시 `false`.
- [ ] 같은 계정으로 2회 로그인해도 `app_user` 행이 1개(중복 생성 없음), `auth_session`은 2행(브라우저별 세션).

**범위 밖(이 페이즈):**

- 프로젝트·멤버십·권한 차단(Phase 2·3). 이 페이즈는 로그인만 하고 아무 기능도 막지 않는다.
- WebSocket(`/ws/erd`)·MCP(`/sse`,`/mcp/**`) 인증. 인터셉터를 태우지 않고 무조건 통과.
- 프론트의 기능 숨김. 헤더에 로그인/로그아웃 버튼과 표시이름만 붙인다.
- 기존 `X-User`(이름) 흐름 제거 — 남겨둔다. 로그인 상태면 표시이름을 우선 사용하되, 미로그인/enabled=false면 기존 `NameModal` 흐름 그대로.

## 2. 개발디테일

**cortex에서 이식(패키지 `com.daou.cortex`→`com.daou.erdstudio`, 쿠키·설정 접두어 변경):**

원본은 `/Users/formi/cortex/backend/src/main/java/com/daou/cortex/auth/` 아래. 아래 파일을 읽고 그대로 옮긴 뒤 네이밍만 치환한다.

신규 — 백엔드 (`backend/src/main/java/com/daou/erdstudio/auth/`):
- `OidcController.java` — cortex 원본 그대로. 쿠키 상수 `cortex_oidc`→`erd_oidc`, 세션 쿠키 참조는 `AuthController.sessionCookie` 사용. `properties.getOidc()` → 아래 `OidcProperties`.
- `OidcService.java` — 그대로. `CortexProperties`→`OidcProperties` 주입.
- `OidcClient.java` — 그대로. `USER_AGENT`는 `"ErdStudio/0.1"`. `CortexProperties.Oidc`→`OidcProperties`.
- `OidcLoginException.java` — 그대로.
- `AuthService.java` — cortex 원본에서 **`ProjectMemberRepository` 의존과 `changeSuperAdmin/deleteUser/listAssignableUsers/requireSuperAdmin` 은 제거**(Phase 3에서 다시 추가). 남길 것: `issueSession`, `resolveOrProvision`, `logout`, `resolve`, `me`, `newToken`, `MeView` record. `Hashing.sha256`는 아래 신규 유틸 사용.
- `Principal.java` — 그대로.
- `AuthContext.java` — 그대로.
- `AuthInterceptor.java` — 그대로. `COOKIE_NAME="erd_session"`.
- `AuthController.java` — cortex 원본에서 **MCP 토큰 관련(`McpTokenService`, `/mcp-token`) 제거**. 남길 것: `/logout`, `/me`, `sessionCookie(...)`. `COOKIE_NAME` 참조는 `AuthInterceptor.COOKIE_NAME`.
- `AppUser.java` — 그대로(`@Table("app_user")`, `BaseTimeEntity` 상속 → 아래 참고).
- `AppUserRepository.java` — `Optional<AppUser> findByOidcSub(String)`, `Optional<AppUser> findByUsername(String)`, `long countBySuperAdminTrue()`.
- `AuthSession.java` — cortex 원본 그대로(`@Table("auth_session")`, `tokenHash`, `user`(@ManyToOne), `expiresAt`).
- `AuthSessionRepository.java` — `Optional<AuthSession> findByTokenHash(String)`, `void deleteByTokenHash(String)`, `void deleteByUserId(Long)`.
- `OidcProperties.java` — `@ConfigurationProperties("erd.oidc")`. 필드: `enabled`(기본 false), `issuer`, `clientId`, `clientSecret`, `redirectUri`, `adminGroup`(기본 `"erd-admins"`). cortex `CortexProperties.Oidc`와 동일.

신규 — 백엔드 공통:
- `backend/src/main/java/com/daou/erdstudio/common/Hashing.java` — `static String sha256(String)` → SHA-256 hex(소문자). cortex `common/Hashing.java` 확인해 동일 시그니처로. 의존성 추가 불필요(`MessageDigest`).
- `backend/src/main/java/com/daou/erdstudio/common/BaseTimeEntity.java` — cortex에 있으면 그대로 이식(`@MappedSuperclass`, `createdAt`/`updatedAt`, `@EnableJpaAuditing` 필요). **cortex에 감사(Auditing) 설정이 있는지 확인**하고, 없거나 과하면 `AppUser`/`AuthSession`에서 `created_at`/`updated_at`을 `@PrePersist`/`@PreUpdate`로 직접 채우는 방식으로 대체(신규 의존성 없이).
- `backend/src/test/java/com/daou/erdstudio/auth/OidcServiceTest.java` — PKCE·state 단위 테스트.

변경 — 백엔드:
- `ErdStudioApplication.java` 또는 config — `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties(OidcProperties.class)` 추가.
- `backend/src/main/java/com/daou/erdstudio/config/` 에 `WebConfig implements WebMvcConfigurer` 신규(없으면) — `AuthInterceptor`를 `/api/**`에 등록. **`/api/health`, `/api/auth/oidc/**` 는 제외**하지 않아도 됨(게스트로 통과). 정적 리소스·`/`(SPA)는 인터셉터 경로에서 자연히 제외.
- CORS/쿠키: 앱이 프론트 정적산출물과 동일 오리진(단일 컨테이너)이므로 CORS 불필요. 확인만.

변경 — 프론트엔드:
- `frontend/src/api/http.ts` — `me(): Promise<Me>`(`GET /api/auth/me`), `logout(): Promise<void>`(`POST /api/auth/logout`) 추가. `Me` 타입 추가. 로그인 시작은 링크 이동(`window.location.href = "/api/auth/oidc/login"`)이라 별도 함수 불필요(헬퍼로 둬도 됨). **모든 fetch에 `credentials` 기본 동일오리진이라 쿠키 자동 포함 — 확인만**.
- `frontend/src/App.tsx` — 마운트 시 `me()` 호출해 `me` 상태 저장. `?login_error=` 쿼리 파싱해 토스트/배너 표시(문구는 cortex `App.tsx`의 `LOGIN_ERRORS` 참고). 로그인 상태를 하위로 전달.
- `frontend/src/components/Header.tsx`(또는 RoomList 헤더) — 미로그인 & `enabled`면 "로그인" 버튼(→ `/api/auth/oidc/login`), 로그인 상태면 표시이름 + "로그아웃" 버튼. `enabled` 여부는 `me` 응답에 담아 내려주거나(권장: `MeView`에 `oidcEnabled` 추가), 별도 `GET /api/auth/config`로.
- `frontend/src/state/user.ts` — 로그인 상태면 `UserInfo.name`을 계정 `displayName`으로 채운다(협업 presence·이력 표기용). 미로그인이면 기존 localStorage 흐름 유지.

**인터페이스/시그니처:**

```java
// OidcProperties.java
@ConfigurationProperties("erd.oidc")
public class OidcProperties {
    private boolean enabled = false;
    private String issuer;         // 끝 슬래시 포함, discovery = issuer + ".well-known/openid-configuration"
    private String clientId;
    private String clientSecret;
    private String redirectUri;    // = <도메인>/api/auth/oidc/callback
    private String adminGroup = "erd-admins";
    // getters/setters
}
```

```java
// AuthService.MeView (Phase 1 형태)
public record MeView(boolean authenticated, Long userId, String username,
                     String displayName, boolean superAdmin, boolean oidcEnabled) {}
// me(): 비로그인 → new MeView(false,null,null,null,false, props.isEnabled())
```

```
app_user     : id, username(uniq,50), display_name(100), oidc_sub(uniq,255,nullable),
               super_admin(bool), super_admin_granted(bool), created_at, updated_at
auth_session : id, token_hash(uniq,64=SHA-256 hex), user_id→app_user(ON DELETE CASCADE),
               expires_at, created_at, updated_at
```
→ **JPA 엔티티로 선언하면 `ddl-auto: update`가 자동 생성**한다. 별도 SQL 마이그레이션 불필요. (unique 제약도 `@Column(unique=true)`/`@Table(uniqueConstraints=...)`로 선언)

```yaml
# application.yml 추가
erd:
  oidc:
    enabled: ${ERD_OIDC_ENABLED:false}
    issuer: ${ERD_OIDC_ISSUER:}
    client-id: ${ERD_OIDC_CLIENT_ID:}
    client-secret: ${ERD_OIDC_CLIENT_SECRET:}
    redirect-uri: ${ERD_OIDC_REDIRECT_URI:}
    admin-group: ${ERD_OIDC_ADMIN_GROUP:erd-admins}
```
`.env.example`에도 위 6개 키를 주석과 함께 추가.

**로그인 성공 후 이동(landing) 주의:** cortex `OidcController.landing()`이 302 대신 200 HTML+JS로 이동하는 이유(브라우저 bounce-tracking이 Set-Cookie를 버리는 문제)를 **그대로 유지**한다. 이 패턴을 임의로 302로 바꾸지 말 것.

## 3. 제약사항

- **`erd.oidc.enabled=false`에서 회귀 0.** 로그인 미설정 상태로 기존 사용자·기존 API·WebSocket·MCP가 지금과 완전히 동일해야 한다. 인터셉터는 게스트를 넣기만 하고 아무것도 막지 않는다.
- **신규 서버 의존성 추가 금지.** OIDC는 `spring-web`의 `RestClient`로 충분(cortex도 그렇게 함). Spring Security 스타터·JWT 라이브러리 도입 금지. 세션은 쿠키+DB 테이블 방식(cortex와 동일).
- **세션 원문 토큰은 DB에 저장 금지** — SHA-256 해시(`token_hash`)만 저장. 원문은 발급 시 쿠키에만.
- **client_secret·access_token·세션 원문 토큰을 로그로 출력 금지.**
- OIDC 콜백의 `landing()` 200-HTML 방식·쿠키 Path(`erd_oidc`는 `/api/auth/oidc`, `erd_session`은 `/`)·SameSite=Lax·Secure 미부착(HTTP 배포 고려)을 cortex와 동일하게 유지.
- 이 페이즈에서 `project*`, `role*`, `PermissionService`, `AccessAdmin` 를 만들지 말 것(Phase 2·3). AuthService에서 프로젝트 의존을 끌어오지 말 것.
- 패키지·클래스명은 cortex 것을 그대로 쓰되 **패키지 접두어만** `com.daou.erdstudio`로. 쿠키·설정 접두어는 `erd_*`/`erd.oidc.*`.

## 4. 테스트방법

1. **빌드/기동(기본, SSO off):**
   ```bash
   cd /Users/formi/adcon-erd && docker-compose up -d --build   # 또는 backend ./gradlew bootRun
   ```
2. **회귀·기본 동작 확인:**
   ```bash
   curl -s -o /dev/null -w 'login=%{http_code}\n' localhost:8080/api/auth/oidc/login   # 503
   curl -s localhost:8080/api/auth/me                                                  # authenticated:false, oidcEnabled:false
   curl -s -o /dev/null -w 'rooms=%{http_code}\n' localhost:8080/api/rooms             # 200
   curl -s -o /dev/null -w 'health=%{http_code}\n' localhost:8080/api/health           # 200
   docker-compose exec db psql -U erd -d erd_studio -c '\dt' | grep -E 'app_user|auth_session'
   ```
3. **단위 테스트:** `cd backend && ./gradlew test --tests '*OidcServiceTest'` → PKCE/state/enabled=false 케이스 통과.
4. **SSO 실경로(수동, authentik 등록 후):** `.env`에 `ERD_OIDC_ENABLED=true` + 나머지 5개 키 채우고 재기동 → 브라우저로 `http://localhost:8080/api/auth/oidc/login` → authentik 로그인 → `/` 복귀 → 헤더에 내 표시이름·로그아웃 버튼 표시, `GET /api/auth/me` `authenticated:true`. 로그아웃 → `false`.
5. **완료 보고:** 위 자동 항목 결과와 (가능하면) 수동 SSO 왕복 스크린샷/로그를 첨부. `enabled=false` 회귀 없음을 명시.

---
**다음 페이즈:** `prompts/access-control/02-projects.md` (프로젝트 도입 + 방 소속화). Phase 1 머지·검증 후 착수.
