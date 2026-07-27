package com.daou.erdstudio.web.dto;

import java.time.Instant;

/** 이력 목록 응답 행 (snapshot 제외). */
public record HistoryEntry(Long id, String userName, String opKind, String target, Instant createdAt) {
}
