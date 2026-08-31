package com.daou.erdstudio.auth;

import com.daou.erdstudio.common.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/** 세션·계정 관리. 신원은 authentik(OIDC)이 판정하고, 세션 토큰은 SHA-256 해시로만 저장한다. */
@Service
public class AuthService {

    /** 세션 유효기간(초). 쿠키 Max-Age 와 auth_session.expires_at 이 같은 값을 쓴다. */
    public static final long SESSION_SECONDS = 2592000L;

    private final AppUserRepository appUserRepository;
    private final AuthSessionRepository authSessionRepository;
    private final OidcProperties oidcProperties;

    private final SecureRandom random = new SecureRandom();

    public AuthService(AppUserRepository appUserRepository,
                       AuthSessionRepository authSessionRepository,
                       OidcProperties oidcProperties) {
        this.appUserRepository = appUserRepository;
        this.authSessionRepository = authSessionRepository;
        this.oidcProperties = oidcProperties;
    }

    /** 세션 행을 만들고 쿠키에 실을 원문 토큰을 반환한다. */
    @Transactional
    public String issueSession(AppUser user) {
        String token = newToken();
        authSessionRepository.save(new AuthSession(
                Hashing.sha256(token), user, LocalDateTime.now().plusSeconds(SESSION_SECONDS)));
        return token;
    }

    /**
     * oidc_sub → username 순으로 계정을 찾고, 찾은 계정에는 표시이름·superAdmin 을 동기화한다.
     * 둘 다 없으면 신규 생성. username 이 이미 다른 sub 에 연결돼 있으면 conflict 로 실패.
     */
    @Transactional
    public AppUser resolveOrProvision(OidcClient.UserInfo info, boolean superAdmin) {
        AppUser bySub = appUserRepository.findByOidcSub(info.sub()).orElse(null);
        if (bySub != null) {
            bySub.syncProfile(info.name(), superAdmin);
            return bySub;
        }
        AppUser byName = appUserRepository.findByUsername(info.preferredUsername()).orElse(null);
        if (byName != null) {
            if (byName.getOidcSub() != null && !byName.getOidcSub().equals(info.sub())) {
                throw new OidcLoginException("conflict", "이미 다른 SSO 계정에 연결된 아이디입니다.");
            }
            byName.linkOidc(info.sub());
            byName.syncProfile(info.name(), superAdmin);
            return byName;
        }
        return appUserRepository.save(
                new AppUser(info.preferredUsername(), info.name(), info.sub(), superAdmin));
    }

    /** 해당 토큰의 세션만 제거한다(같은 계정의 다른 브라우저 세션은 유지). */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            authSessionRepository.deleteByTokenHash(Hashing.sha256(rawToken));
        }
    }

    /** 쿠키 토큰 → 요청 주체. 토큰이 없거나 만료면 게스트를 반환하고 만료 행은 제거한다. */
    @Transactional
    public Principal resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Principal.guest();
        }
        AuthSession session = authSessionRepository.findByTokenHash(Hashing.sha256(rawToken)).orElse(null);
        if (session == null) {
            return Principal.guest();
        }
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            authSessionRepository.delete(session);
            return Principal.guest();
        }
        AppUser user = session.getUser();
        return new Principal(user.getId(), user.getUsername(), user.isSuperAdmin());
    }

    @Transactional(readOnly = true)
    public MeView me(Principal principal) {
        boolean oidcEnabled = oidcProperties.isEnabled();
        if (principal == null || !principal.authenticated()) {
            return new MeView(false, null, null, null, false, oidcEnabled);
        }
        return appUserRepository.findById(principal.userId())
                .map(u -> new MeView(true, u.getId(), u.getUsername(), u.getDisplayName(),
                        u.isSuperAdmin(), oidcEnabled))
                .orElseGet(() -> new MeView(false, null, null, null, false, oidcEnabled));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** oidcEnabled 는 프론트가 로그인 버튼 노출 여부를 결정하는 데 쓴다. */
    public record MeView(boolean authenticated, Long userId, String username,
                         String displayName, boolean superAdmin, boolean oidcEnabled) {
    }
}
