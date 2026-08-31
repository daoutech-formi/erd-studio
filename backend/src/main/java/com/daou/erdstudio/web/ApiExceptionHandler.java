package com.daou.erdstudio.web;

import com.daou.erdstudio.common.ForbiddenException;
import com.daou.erdstudio.common.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** REST 오류를 구 서버와 동일한 {error: 메시지} 형태로 응답한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthorized(UnauthorizedException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden(ForbiddenException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * 없는 경로 요청 — 구버전 번들을 띄워 둔 브라우저 탭이 옛 API(/api/schema 등)를 부르는 경우가
     * 대부분이라 스택트레이스 없이 한 줄만 남긴다. (해당 탭을 새로고침하면 사라진다)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NoResourceFoundException e) {
        log.warn("존재하지 않는 경로 요청: /{}", e.getResourcePath());
        return Map.of("error", "존재하지 않는 API 입니다. 화면을 새로고침해 주세요.");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> serverError(Exception e) {
        log.error("요청 처리 실패", e);
        return Map.of("error", "서버 오류가 발생했습니다.");
    }
}
