package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdHistory;
import com.daou.erdstudio.repository.ErdHistoryRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.HistoryEntry;
import com.daou.erdstudio.web.dto.Op;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class HistoryAndSeedTest {

    @Autowired
    OpService opService;
    @Autowired
    HistoryService historyService;
    @Autowired
    HistoryRecorder historyRecorder;
    @Autowired
    ErdHistoryRepository historyRepository;
    @Autowired
    ErdTableRepository tableRepository;
    @Autowired
    ErdRelationRepository relationRepository;
    @Autowired
    ObjectMapper objectMapper;

    private Op op(String type, Map<String, Object> payload) {
        return new Op(type, "tester", objectMapper.valueToTree(payload));
    }

    @Test
    void 시드가_적재되어_테이블107_관계122_도메인10이_존재한다() {
        assertThat(tableRepository.count()).isEqualTo(107);
        assertThat(relationRepository.count()).isEqualTo(122);
    }

    @Test
    void op_적용마다_이력이_기록된다() {
        long before = historyRepository.count();
        opService.apply(op("table.add", Map.of("name", "t_hist_a", "domain", "user", "desc", "")));
        List<HistoryEntry> entries = historyService.list(1);
        assertThat(historyRepository.count()).isEqualTo(before + 1);
        assertThat(entries.get(0).opKind()).isEqualTo("table.add");
        assertThat(entries.get(0).target()).isEqualTo("t_hist_a");
        assertThat(entries.get(0).userName()).isEqualTo("tester");
    }

    @Test
    void 이력은_최대_1000행으로_유지된다() {
        List<ErdHistory> bulk = new ArrayList<>();
        for (int i = 0; i < 1005; i++) {
            bulk.add(new ErdHistory("tester", "table.move", "t" + i, "{}", "{}"));
        }
        historyRepository.saveAll(bulk);
        historyRecorder.record("tester", "table.move", "trim-trigger", Map.of());
        assertThat(historyRepository.count()).isLessThanOrEqualTo(HistoryRecorder.MAX_ROWS);
    }

    @Test
    void 복원하면_스냅샷_시점_상태로_되돌아간다() {
        opService.apply(op("table.add", Map.of("name", "t_hist_restore", "domain", "user", "desc", "")));
        long snapshotId = historyService.list(1).get(0).id();
        opService.apply(op("table.delete", Map.of("name", "t_hist_restore")));
        assertThat(tableRepository.findByName("t_hist_restore")).isEmpty();

        historyService.restore(snapshotId, "tester");

        assertThat(tableRepository.findByName("t_hist_restore")).isPresent();
        assertThat(historyService.list(1).get(0).opKind()).isEqualTo("history.restore");
    }

    @Test
    void 없는_이력_복원은_거부한다() {
        assertThatThrownBy(() -> historyService.restore(9_999_999L, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이력");
    }

    @Test
    void 이력_목록은_최신순이고_limit을_지킨다() {
        opService.apply(op("table.add", Map.of("name", "t_hist_l1", "domain", "user", "desc", "")));
        opService.apply(op("table.add", Map.of("name", "t_hist_l2", "domain", "user", "desc", "")));
        List<HistoryEntry> entries = historyService.list(2);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).id()).isGreaterThan(entries.get(1).id());
        assertThat(entries.get(0).target()).isEqualTo("t_hist_l2");
    }
}
