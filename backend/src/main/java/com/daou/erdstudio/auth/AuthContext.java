package com.daou.erdstudio.auth;

/**
 * 요청 스레드의 인증 주체를 담는 ThreadLocal.
 * AuthInterceptor 가 쿠키를 해석해 설정하고 요청 종료 시 제거한다.
 * 인터셉터를 타지 않는 경로(WebSocket·MCP·스케줄러)에서는 게스트로 해석된다.
 */
public final class AuthContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    /** 설정된 주체가 없으면 게스트를 반환한다(null 을 돌려주지 않는다). */
    public static Principal get() {
        Principal p = CURRENT.get();
        return p != null ? p : Principal.guest();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
