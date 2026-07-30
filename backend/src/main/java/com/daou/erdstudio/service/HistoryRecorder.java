package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdHistory;
import com.daou.erdstudio.repository.ErdHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** op 적용 직후의 스냅샷과 함께 변경 이력을 기록하고, 방별 보관 상한을 유지한다. */
@Component
public class HistoryRecorder {

    /** 방 하나당 보관하는 최대 이력 행 수. */
    public static final int MAX_ROWS = 1000;

    private final ErdHistoryRepository historyRepository;
    private final SchemaService schemaService;
    private final ObjectMapper objectMapper;

    public HistoryRecorder(ErdHistoryRepository historyRepository, SchemaService schemaService,
                           ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.schemaService = schemaService;
        this.objectMapper = objectMapper;
    }

    /** 호출측 트랜잭션 안에서 실행된다 — op 반영과 이력 기록은 함께 성공/실패한다. */
    public void record(Long roomId, String userName, String opKind, String target, Object opBody) {
        try {
            String opJson = objectMapper.writeValueAsString(opBody);
            String snapshot = objectMapper.writeValueAsString(schemaService.loadDoc(roomId));
            historyRepository.save(new ErdHistory(roomId, safeName(userName), opKind, target, opJson, snapshot));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이력 직렬화 실패", e);
        }
        trim(roomId);
    }

    private void trim(Long roomId) {
        List<Long> ids = historyRepository.findIdsByRoomIdOrderByIdDesc(roomId);
        if (ids.size() > MAX_ROWS) {
            historyRepository.deleteAllByIdInBatch(ids.subList(MAX_ROWS, ids.size()));
        }
    }

    private String safeName(String userName) {
        if (userName == null || userName.isBlank()) {
            return "unknown";
        }
        return userName.length() > 20 ? userName.substring(0, 20) : userName;
    }
}
