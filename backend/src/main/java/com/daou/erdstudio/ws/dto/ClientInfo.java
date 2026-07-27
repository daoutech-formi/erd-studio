package com.daou.erdstudio.ws.dto;

/** 접속자 식별 정보 — id는 WebSocket 세션 id. */
public record ClientInfo(String id, String user, String color) {
}
