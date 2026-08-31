package com.daou.erdstudio.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OIDC 플로우의 외부 호출 없는 부분 — PKCE 챌린지, state 검증, enabled=false 진입 차단. */
class OidcServiceTest {

    @Test
    @DisplayName("PKCE S256 챌린지는 base64url(SHA-256(verifier), 패딩 없음)이다")
    void challengeIsBase64UrlSha256() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        assertThat(OidcService.challenge(verifier)).isEqualTo(expected);
        assertThat(OidcService.challenge(verifier)).doesNotContain("=", "+", "/");
    }

    @Test
    @DisplayName("쿼리 state 와 쿠키 state 가 다르면 code 교환 없이 state 오류로 실패한다")
    void completeRejectsMismatchedState() {
        // client/authService 는 state 검증 단계에서 던지므로 호출되지 않는다.
        OidcService service = new OidcService(null, null, new OidcProperties());

        assertThatThrownBy(() -> service.complete("any-code", "state-A", "state-B.verifier"))
                .isInstanceOf(OidcLoginException.class)
                .satisfies(e -> assertThat(((OidcLoginException) e).getCode()).isEqualTo("state"));
    }

    @Test
    @DisplayName("state 쿠키가 없거나 형식이 깨져도 state 오류로 실패한다")
    void completeRejectsMissingOrBrokenCookie() {
        OidcService service = new OidcService(null, null, new OidcProperties());

        assertThatThrownBy(() -> service.complete("c", "s", null))
                .isInstanceOf(OidcLoginException.class)
                .satisfies(e -> assertThat(((OidcLoginException) e).getCode()).isEqualTo("state"));
        assertThatThrownBy(() -> service.complete("c", "s", "no-dot-value"))
                .isInstanceOf(OidcLoginException.class)
                .satisfies(e -> assertThat(((OidcLoginException) e).getCode()).isEqualTo("state"));
    }

    @Test
    @DisplayName("enabled=false 면 로그인 진입점이 503 을 반환한다")
    void loginReturns503WhenDisabled() throws Exception {
        OidcProperties props = new OidcProperties();   // enabled 기본값 false
        OidcController controller = new OidcController(new OidcService(null, null, props), props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login(response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    @DisplayName("enabled=false 면 콜백도 503 이다(상태 쿠키 제거는 수행)")
    void callbackReturns503WhenDisabled() throws Exception {
        OidcProperties props = new OidcProperties();
        OidcController controller = new OidcController(new OidcService(null, null, props), props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.callback("code", "state", "s.v", response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
}
