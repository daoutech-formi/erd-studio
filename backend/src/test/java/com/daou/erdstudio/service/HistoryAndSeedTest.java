package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdHistory;
import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.repository.ErdHistoryRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.HistoryEntry;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    SchemaService schemaService;
    @Autowired
    ErdHistoryRepository historyRepository;
    @Autowired
    ErdTableRepository tableRepository;
    @Autowired
    ErdRelationRepository relationRepository;
    @Autowired
    ErdRoomRepository roomRepository;
    @Autowired
    ObjectMapper objectMapper;

    private Long roomId;

    @BeforeEach
    void createRoom() {
        ErdRoom room = roomRepository.save(new ErdRoom("t_hist_room", "tester"));
        roomId = room.getId();
        LinkedHashMap<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("user", new SchemaDoc.DomainDef("회원/인증", "#4f8cff"));
        schemaService.replaceAll(roomId, new SchemaDoc(domains, List.of(), List.of(), Map.of()));
    }

    private Op op(String type, Map<String, Object> payload) {
        return new Op(type, "tester", objectMapper.valueToTree(payload));
    }

    @Test
    void 시드가_기본_방에_테이블107_관계122로_적재된다() {
        // 가장 먼저 만들어진 방이 SeedLoader 의 기본 방이다
        ErdRoom seedRoom = roomRepository.findAllByOrderByIdAsc().get(0);
        assertThat(tableRepository.countByRoomId(seedRoom.getId())).isEqualTo(107);
        assertThat(relationRepository.findByRoomIdOrderBySortOrderAsc(seedRoom.getId())).hasSize(122);
    }

    @Test
    void op_적용마다_이력이_기록된다() {
        long before = historyRepository.count();
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_a", "domain", "user", "desc", "")));
        List<HistoryEntry> entries = historyService.list(roomId, 1);
        assertThat(historyRepository.count()).isEqualTo(before + 1);
        assertThat(entries.get(0).opKind()).isEqualTo("table.add");
        assertThat(entries.get(0).target()).isEqualTo("t_hist_a");
        assertThat(entries.get(0).userName()).isEqualTo("tester");
    }

    @Test
    void 이력은_방마다_최대_1000행으로_유지된다() {
        List<ErdHistory> bulk = new ArrayList<>();
        for (int i = 0; i < 1005; i++) {
            bulk.add(new ErdHistory(roomId, "tester", "table.move", "t" + i, "{}", "{}"));
        }
        historyRepository.saveAll(bulk);
        historyRecorder.record(roomId, "tester", "table.move", "trim-trigger", Map.of());
        assertThat(historyRepository.count()).isLessThanOrEqualTo(HistoryRecorder.MAX_ROWS);
    }

    @Test
    void 복원하면_스냅샷_시점_상태로_되돌아간다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_restore", "domain", "user", "desc", "")));
        long snapshotId = historyService.list(roomId, 1).get(0).id();
        opService.apply(roomId, op("table.delete", Map.of("name", "t_hist_restore")));
        assertThat(tableRepository.findByRoomIdAndName(roomId, "t_hist_restore")).isEmpty();

        historyService.restore(roomId, snapshotId, "tester");

        assertThat(tableRepository.findByRoomIdAndName(roomId, "t_hist_restore")).isPresent();
        assertThat(historyService.list(roomId, 1).get(0).opKind()).isEqualTo("history.restore");
    }

    @Test
    void diff는_선택_이력과_직전_이력의_스냅샷_쌍을_돌려준다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_d1", "domain", "user", "desc", "")));
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_d2", "domain", "user", "desc", "")));
        long latestId = historyService.list(roomId, 1).get(0).id();

        var diff = historyService.diff(roomId, latestId);

        assertThat(diff.entry().id()).isEqualTo(latestId);
        assertThat(diff.after().tables()).hasSize(2);
        assertThat(diff.before()).isNotNull();
        assertThat(diff.before().tables()).hasSize(1);
    }

    @Test
    void 첫_이력의_diff는_before가_null이다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_first", "domain", "user", "desc", "")));
        List<HistoryEntry> entries = historyService.list(roomId, 200);
        long firstId = entries.get(entries.size() - 1).id();

        var diff = historyService.diff(roomId, firstId);

        assertThat(diff.before()).isNull();
        assertThat(diff.after()).isNotNull();
    }

    @Test
    void 다른_방의_이력은_diff할_수_없다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_dx", "domain", "user", "desc", "")));
        long historyId = historyService.list(roomId, 1).get(0).id();
        ErdRoom other = roomRepository.save(new ErdRoom("t_hist_room_diff", "tester"));

        assertThatThrownBy(() -> historyService.diff(other.getId(), historyId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이력");
    }

    @Test
    void 없는_이력_복원은_거부한다() {
        assertThatThrownBy(() -> historyService.restore(roomId, 9_999_999L, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이력");
    }

    @Test
    void 다른_방의_이력은_복원할_수_없다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_other", "domain", "user", "desc", "")));
        long historyId = historyService.list(roomId, 1).get(0).id();
        ErdRoom other = roomRepository.save(new ErdRoom("t_hist_room_other", "tester"));

        assertThatThrownBy(() -> historyService.restore(other.getId(), historyId, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이력");
    }

    @Test
    void 이력_목록은_방_안에서_최신순이고_limit을_지킨다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_l1", "domain", "user", "desc", "")));
        opService.apply(roomId, op("table.add", Map.of("name", "t_hist_l2", "domain", "user", "desc", "")));
        List<HistoryEntry> entries = historyService.list(roomId, 2);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).id()).isGreaterThan(entries.get(1).id());
        assertThat(entries.get(0).target()).isEqualTo("t_hist_l2");
    }
}
