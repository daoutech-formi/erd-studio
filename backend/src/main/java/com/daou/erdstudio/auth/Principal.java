package com.daou.erdstudio.auth;

/** 요청 주체. userId 가 null 이면 비로그인 게스트다. */
public record Principal(Long userId, String username, boolean superAdmin) {

    private static final Principal GUEST = new Principal(null, null, false);

    public static Principal guest() {
        return GUEST;
    }

    public boolean authenticated() {
        return userId != null;
    }
}
