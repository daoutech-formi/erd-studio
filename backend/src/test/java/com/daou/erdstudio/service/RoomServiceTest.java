package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.domain.ErdTable;
import com.daou.erdstudio.repository.ErdColumnRepository;
import com.daou.erdstudio.repository.ErdDomainRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.SchemaDoc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class RoomServiceTest {

    private static final int MAX_ROOMS = 20;

    @Autowired
    RoomService roomService;
    @Autowired
    SchemaService schemaService;
    @Autowired
    ErdRoomRepository roomRepository;
    @Autowired
    ErdTableRepository tableRepository;
    @Autowired
    ErdDomainRepository domainRepository;
    @Autowired
    ErdRelationRepository relationRepository;
    @Autowired
    ErdColumnRepository columnRepository;

    /** 테이블 2개 + 관계 1개 + 컬럼이 있는 작은 문서. */
    private SchemaDoc sampleDoc() {
        LinkedHashMap<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("d1", new SchemaDoc.DomainDef("도메인1", "#111111"));
        return new SchemaDoc(domains,
                List.of(List.of("r_child", "d1", "자식"), List.of("r_parent", "d1", "부모")),
                List.of(List.of("r_child", "r_parent", "참조")),
                Map.of("r_child", List.of(List.of("id", "int", "번호", "PK"))));
    }

    @Test
    void 방을_만들면_목록에_추가되고_기본_도메인이_생긴다() {
        ErdRoom room = roomService.create("설계 회의방", "홍길동");

        assertThat(room.getId()).isNotNull();
        assertThat(room.getCreatedBy()).isEqualTo("홍길동");
        assertThat(room.getCreatedAt()).isNotNull();
        assertThat(roomService.list()).extracting(ErdRoom::getName).contains("설계 회의방");
        assertThat(domainRepository.findByRoomIdOrderBySortOrderAsc(room.getId())).hasSize(1);
    }

    @Test
    void 이름을_바꾸면_같은_브라우저로_만든_방의_생성자명이_갱신된다() {
        ErdRoom mine = roomService.create("내 방", "옛이름", "ck-rename-test");
        ErdRoom others = roomService.create("남의 방", "옛이름", "ck-someone-else");
        ErdRoom legacy = roomService.create("구버전 방", "옛이름"); // clientKey 없음

        int updated = roomService.renameCreator("ck-rename-test", "새이름");

        assertThat(updated).isEqualTo(1);
        assertThat(roomRepositoryFind(mine).getCreatedBy()).isEqualTo("새이름");
        assertThat(roomRepositoryFind(others).getCreatedBy()).isEqualTo("옛이름");
        assertThat(roomRepositoryFind(legacy).getCreatedBy()).isEqualTo("옛이름");
    }

    @Test
    void 이름_변경은_브라우저_식별자가_없으면_거부한다() {
        assertThatThrownBy(() -> roomService.renameCreator("", "새이름"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("식별자");
    }

    private ErdRoom roomRepositoryFind(ErdRoom room) {
        return roomRepository.findById(room.getId()).orElseThrow();
    }

    @Test
    void 중복된_방_이름은_거부한다() {
        roomService.create("중복방", "tester");
        assertThatThrownBy(() -> roomService.create("중복방", "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 존재하는 방 이름입니다.");
    }

    @Test
    void 빈_방_이름은_거부한다() {
        assertThatThrownBy(() -> roomService.create("   ", "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("방 이름을 입력하세요.");
        assertThatThrownBy(() -> roomService.create(null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("방 이름을 입력하세요.");
    }

    @Test
    void 방은_최대_20개까지만_만들_수_있다() {
        for (long i = roomRepository.count(); i < MAX_ROOMS; i++) {
            roomService.create("정원방" + i, "tester");
        }
        assertThat(roomRepository.count()).isEqualTo(MAX_ROOMS);

        assertThatThrownBy(() -> roomService.create("스물한번째방", "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("방은 최대 20개까지 만들 수 있습니다.");
    }

    @Test
    void 없는_방이면_거부한다() {
        assertThatThrownBy(() -> roomService.requireExists(9_999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 방입니다.");
        assertThatThrownBy(() -> roomService.delete(9_999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 방입니다.");
    }

    @Test
    void 방을_삭제하면_그_방의_데이터만_사라진다() {
        ErdRoom target = roomService.create("삭제될방", "tester");
        ErdRoom keep = roomService.create("유지될방", "tester");
        schemaService.replaceAll(target.getId(), sampleDoc());
        schemaService.replaceAll(keep.getId(), sampleDoc());
        List<Long> targetTableIds = tableRepository.findByRoomIdOrderBySortOrderAsc(target.getId()).stream()
                .map(ErdTable::getId)
                .toList();
        assertThat(targetTableIds).hasSize(2);

        roomService.delete(target.getId());

        assertThat(roomRepository.findById(target.getId())).isEmpty();
        assertThat(tableRepository.findByRoomIdOrderBySortOrderAsc(target.getId())).isEmpty();
        assertThat(domainRepository.findByRoomIdOrderBySortOrderAsc(target.getId())).isEmpty();
        assertThat(relationRepository.findByRoomIdOrderBySortOrderAsc(target.getId())).isEmpty();
        assertThat(columnRepository.findByTableIds(targetTableIds)).isEmpty();

        // 다른 방은 그대로 남아 있다
        assertThat(roomRepository.findById(keep.getId())).isPresent();
        SchemaDoc kept = schemaService.loadDoc(keep.getId());
        assertThat(kept.tables()).hasSize(2);
        assertThat(kept.relations()).hasSize(1);
        assertThat(kept.columns().get("r_child")).hasSize(1);
        assertThat(kept.domains()).containsOnlyKeys("d1");
    }
}
