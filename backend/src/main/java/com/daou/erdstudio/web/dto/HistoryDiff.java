package com.daou.erdstudio.web.dto;

/**
 * 이력 diff 응답 — 선택한 이력의 스냅샷(after)과 직전 이력의 스냅샷(before).
 * before는 해당 방의 첫 이력이면 null이다(프론트에서 빈 문서로 간주해 전부 추가로 표시).
 */
public record HistoryDiff(HistoryEntry entry, SchemaDoc before, SchemaDoc after) {
}
