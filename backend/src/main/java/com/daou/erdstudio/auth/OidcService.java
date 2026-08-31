package com.daou.erdstudio.auth;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * authentik OIDC Authorization Code + PKCE 플로우.
 * state·code_verifier 는 서버에 저장하지 않고 HttpOnly 쿠키(erd_oidc)에 왕복시킨다.
 */
@Service
public class OidcService {

    private static final String SCOPE = "openid profile email";
    private static final int USERNAME_MIN = 2;
    private static final int USERNAME_MAX = 50;
    private static final int DISPLAY_NAME_MAX = 100;

    private final OidcClient client;
    private final AuthService authService;
    private final OidcProperties props;

    private final SecureRandom random = new SecureRandom();

    public OidcService(OidcClient client, AuthService authService, OidcProperties props) {
        this.client = client;
        this.authService = authService;
        this.props = props;
    }

    /** authorize URL 과 erd_oidc 쿠키에 실을 "state.verifier" 값을 반환한다. */
    public Begin begin() {
        String state = randomValue();
        String verifier = randomValue();
        String url = UriComponentsBuilder.fromUriString(client.discovery().authorizationEndpoint())
                .queryParam("client_id", props.getClientId())
                .queryParam("redirect_uri", props.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUriString();
        return new Begin(url, state + "." + verifier);
    }

    /** state 검증 → 코드교환 → userinfo → 계정 연결/생성 → erd_session 원문 토큰 반환. */
    public String complete(String code, String state, String oidcCookie) {
        String verifier = verifyState(state, oidcCookie);
        if (code == null || code.isBlank()) {
            throw new OidcLoginException("exchange", "authorization code 가 없습니다.");
        }
        String accessToken;
        try {
            accessToken = client.exchangeCode(code, verifier);
        } catch (RuntimeException e) {
            throw new OidcLoginException("exchange", "토큰 교환에 실패했습니다.", e);
        }
        OidcClient.UserInfo raw;
        try {
            raw = client.userInfo(accessToken);
        } catch (RuntimeException e) {
            throw new OidcLoginException("profile", "신원 조회에 실패했습니다.", e);
        }
        OidcClient.UserInfo info = normalize(raw);
        boolean superAdmin = info.groups().contains(props.getAdminGroup());
        AppUser user = authService.resolveOrProvision(info, superAdmin);
        return authService.issueSession(user);
    }

    /** 쿠키의 "state.verifier" 를 분해하고 쿼리 state 와 일치하는지 확인한 뒤 verifier 를 반환한다. */
    private static String verifyState(String state, String oidcCookie) {
        if (state == null || state.isBlank() || oidcCookie == null || oidcCookie.isBlank()) {
            throw new OidcLoginException("state", "로그인 요청 상태값이 없습니다.");
        }
        int dot = oidcCookie.indexOf('.');
        if (dot <= 0 || dot == oidcCookie.length() - 1) {
            throw new OidcLoginException("state", "로그인 요청 상태값이 손상되었습니다.");
        }
        String expected = oidcCookie.substring(0, dot);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            throw new OidcLoginException("state", "로그인 요청 상태값이 일치하지 않습니다.");
        }
        return oidcCookie.substring(dot + 1);
    }

    /** sub·아이디를 검증하고 표시이름을 app_user 컬럼 길이에 맞춘다. */
    private static OidcClient.UserInfo normalize(OidcClient.UserInfo info) {
        String sub = info.sub() == null ? "" : info.sub().trim();
        if (sub.isEmpty()) {
            throw new OidcLoginException("profile", "IdP 응답에 sub 가 없습니다.");
        }
        String username = info.preferredUsername() == null ? "" : info.preferredUsername().trim();
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            throw new OidcLoginException("username", "사용할 수 없는 아이디 길이입니다.");
        }
        String name = info.name() == null ? "" : info.name().trim();
        if (name.isEmpty()) {
            name = username;
        } else if (name.length() > DISPLAY_NAME_MAX) {
            name = name.substring(0, DISPLAY_NAME_MAX);
        }
        List<String> groups = info.groups() == null ? List.of() : info.groups();
        return new OidcClient.UserInfo(sub, username, name, groups);
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** PKCE S256: SHA-256(verifier) → base64url(패딩 없음). */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record Begin(String authorizeUrl, String stateCookieValue) {
    }
}
