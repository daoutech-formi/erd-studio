package com.daou.erdstudio.ws.dto;

/**
 * 접속자 식별 정보.
 * id 는 WebSocket 세션 id(탭 단위), clientKey 는 브라우저 단위 식별자(여러 탭이 같은 값)다.
 * 정원 계산과 접속자 목록은 clientKey 기준으로 중복을 제거한다.
 */
public record ClientInfo(String id, String clientKey, String user, String color) {
}
