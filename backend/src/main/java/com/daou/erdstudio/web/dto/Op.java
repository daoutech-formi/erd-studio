package com.daou.erdstudio.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 편집 연산 단위. WebSocket과 HTTP(PUT /api/schema)가 공용으로 사용한다.
 * type: table.add | table.apply | table.delete | table.move | schema.replace
 */
public record Op(String type, String user, JsonNode payload) {
}
