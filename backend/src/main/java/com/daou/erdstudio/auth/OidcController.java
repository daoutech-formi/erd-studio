package com.daou.erdstudio.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * authentik SSO 진입점·콜백.
 * 쿠키를 심고 바로 302 하면 브라우저 bounce-tracking 이 Set-Cookie 를 버릴 수 있어,
 * 로그인·성공 콜백은 200 HTML 로 이동한다. 실패는 /?login_error={code} 302.
 */
@RestController
@RequestMapping("/api/auth/oidc")
public class OidcController {

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    /** state + PKCE verifier 왕복용 임시 쿠키. 콜백 경로에서만 전송된다. */
    static final String COOKIE_NAME = "erd_oidc";
    private static final String COOKIE_PATH = "/api/auth/oidc";
    private static final long COOKIE_SECONDS = 300L;
    static final String LOGIN_PATH = "/api/auth/oidc/login";

    private final OidcService oidcService;
    private final OidcProperties properties;

    public OidcController(OidcService oidcService, OidcProperties properties) {
        this.oidcService = oidcService;
        this.properties = properties;
    }

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        if (!properties.isEnabled()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        OidcService.Begin begin = oidcService.begin();
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie(begin.stateCookieValue(), COOKIE_SECONDS));
        landing(response, begin.authorizeUrl());
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @CookieValue(name = COOKIE_NAME, required = false) String oidcCookie,
                         HttpServletResponse response) throws IOException {
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", 0));
        if (!properties.isEnabled()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        // authentik 앱 클릭(Launch URL=callback)은 PKCE 쿠키 없이 들어온다. SP 플로우로 다시 탄다.
        if (oidcCookie == null || oidcCookie.isBlank()) {
            redirect(response, LOGIN_PATH);
            return;
        }
        try {
            String token = oidcService.complete(code, state, oidcCookie);
            response.addHeader(HttpHeaders.SET_COOKIE,
                    AuthController.sessionCookie(token, AuthService.SESSION_SECONDS).toString());
            landing(response, "/");
        } catch (OidcLoginException e) {
            log.warn("SSO 로그인 실패: login_error={}", e.getCode());
            redirect(response, "/?login_error=" + e.getCode());
        } catch (RuntimeException e) {
            log.warn("SSO 로그인 실패: login_error=exchange");
            redirect(response, "/?login_error=exchange");
        }
    }

    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, location);
    }

    /**
     * Set-Cookie 가 붙은 채 문서를 커밋한 뒤 JS 로 이동한다.
     * 즉시 302 하면 Chrome/Safari 가 bounce 로 보고 방금 심은 쿠키를 지운다.
     */
    static void landing(HttpServletResponse response, String location) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=" + htmlAttr(location) + "\">"
                + "</head><body><script>location.replace(" + jsString(location) + ")</script></body></html>");
    }

    static String jsString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '"' -> sb.append('\\').append(c);
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String htmlAttr(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 콜백 경로에서만 전송되도록 Path 를 좁힌다. 만료 재발급도 같은 Path 를 써야 브라우저가 지운다. */
    private static String stateCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .path(COOKIE_PATH)
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build()
                .toString();
    }
}
