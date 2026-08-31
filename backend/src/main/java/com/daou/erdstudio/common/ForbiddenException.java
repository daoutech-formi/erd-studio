package com.daou.erdstudio.common;

/** 로그인은 했지만 권한이 부족한 요청 — 403 으로 응답한다. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
