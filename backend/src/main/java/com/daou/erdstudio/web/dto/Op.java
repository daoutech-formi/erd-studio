package com.daou.erdstudio.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 편집 연산 단위. WebSocket과 HTTP(PUT /api/rooms/{roomId}/schema)가 공용으로 사용한다.
 * type: table.add | table.apply | table.delete | table.move | schema.replace
 * 대상 방(roomId)은 payload가 아니라 WebSocket 세션 또는 URL 경로에서 결정되어 별도 인자로 전달된다.
 */
public record Op(String type, String user, JsonNode payload) {
}
