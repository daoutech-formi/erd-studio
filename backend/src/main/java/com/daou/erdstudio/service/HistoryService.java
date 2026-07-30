package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdHistory;
import com.daou.erdstudio.repository.ErdHistoryRepository;
import com.daou.erdstudio.web.dto.HistoryEntry;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 방별 변경 이력 조회와 특정 시점 복원. */
@Service
public class HistoryService {

    private static final int MAX_LIST_LIMIT = 200;

    private final ErdHistoryRepository historyRepository;
    private final SchemaService schemaService;
    private final HistoryRecorder historyRecorder;
    private final ObjectMapper objectMapper;

    public HistoryService(ErdHistoryRepository historyRepository, SchemaService schemaService,
                          HistoryRecorder historyRecorder, ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.schemaService = schemaService;
        this.historyRecorder = historyRecorder;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> list(Long roomId, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return historyRepository.findByRoomIdOrderByIdDesc(roomId, PageRequest.of(0, size)).stream()
                .map(h -> new HistoryEntry(h.getId(), h.getUserName(), h.getOpKind(), h.getTarget(), h.getCreatedAt()))
                .toList();
    }

    /** 해당 이력의 스냅샷으로 그 방의 전체 스키마를 되돌리고, 복원 자체를 이력으로 남긴다. */
    @Transactional
    public SchemaDoc restore(Long roomId, long historyId, String userName) {
        ErdHistory history = historyRepository.findByIdAndRoomId(historyId, roomId).orElseThrow(
                () -> new IllegalArgumentException("이력을 찾을 수 없습니다: " + historyId));
        SchemaDoc doc = parseSnapshot(history.getSnapshot());
        schemaService.replaceAll(roomId, doc);
        historyRecorder.record(roomId, userName, "history.restore", "#" + historyId, Map.of("historyId", historyId));
        return doc;
    }

    private SchemaDoc parseSnapshot(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, SchemaDoc.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스냅샷 파싱 실패", e);
        }
    }
}
