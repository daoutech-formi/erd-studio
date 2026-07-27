package com.daou.erdstudio.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * X-User 헤더 디코딩 — HTTP 헤더는 latin-1로 해석되므로
 * 클라이언트가 URL 인코딩해 보낸 한글 이름을 UTF-8로 복원한다.
 */
public final class UserHeader {

    private UserHeader() {
    }

    public static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }
}
