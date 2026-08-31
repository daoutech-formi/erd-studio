package com.daou.erdstudio.auth;

import com.daou.erdstudio.auth.AuthService.MeView;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그아웃/현재 로그인 상태 조회. 로그인 진입은 OidcController 가 담당한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/logout")
    public void logout(@CookieValue(name = AuthInterceptor.COOKIE_NAME, required = false) String token,
                       HttpServletResponse response) {
        authService.logout(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
    }

    @GetMapping("/me")
    public MeView me() {
        return authService.me(AuthContext.get());
    }

    /** 배포가 HTTP 인 환경을 고려해 Secure 는 붙이지 않는다. */
    static ResponseCookie sessionCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(AuthInterceptor.COOKIE_NAME, value)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
