package com.daou.erdstudio.common;

/** 로그인이 필요한 요청에 게스트로 접근 — 401 로 응답한다(프론트가 로그인 유도). */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
