package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.domain.ErdTable;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
class OpServiceTest {

    @Autowired
    OpService opService;
    @Autowired
    ErdTableRepository tableRepository;
    @Autowired
    ErdRoomRepository roomRepository;
    @Autowired
    SchemaService schemaService;
    @Autowired
    ObjectMapper objectMapper;

    private Long roomId;

    /** 테스트 전용 방 — 다른 방(시드 방)의 데이터와 섞이지 않는다. */
    @BeforeEach
    void createRoom() {
        ErdRoom room = roomRepository.save(new ErdRoom("t_optest_room", "tester"));
        roomId = room.getId();
        LinkedHashMap<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("user", new SchemaDoc.DomainDef("회원/인증", "#4f8cff"));
        domains.put("goods", new SchemaDoc.DomainDef("상품/브랜드", "#37c98b"));
        schemaService.replaceAll(roomId, new SchemaDoc(domains, List.of(), List.of(), Map.of()));
    }

    private Op op(String type, Map<String, Object> payload) {
        return new Op(type, "tester", objectMapper.valueToTree(payload));
    }

    private void addTable(String name) {
        opService.apply(roomId, op("table.add", Map.of("name", name, "domain", "user", "desc", "테스트")));
    }

    @Test
    void tableAdd_새_테이블을_생성한다() {
        addTable("t_optest_a");
        ErdTable saved = tableRepository.findByRoomIdAndName(roomId, "t_optest_a").orElseThrow();
        assertThat(saved.getRoomId()).isEqualTo(roomId);
        assertThat(saved.getDomainKey()).isEqualTo("user");
        assertThat(saved.getDescription()).isEqualTo("테스트");
    }

    @Test
    void tableAdd_도메인이_비어있으면_방의_첫_도메인으로_보정한다() {
        opService.apply(roomId, op("table.add", Map.of("name", "t_optest_nodomain", "desc", "도메인 미지정")));
        ErdTable saved = tableRepository.findByRoomIdAndName(roomId, "t_optest_nodomain").orElseThrow();
        assertThat(saved.getDomainKey()).isEqualTo("user");
    }

    @Test
    void tableAdd_방에_도메인이_없으면_기본도메인을_만들어_추가한다() {
        ErdRoom empty = roomRepository.save(new ErdRoom("t_optest_empty_room", "tester"));
        opService.apply(empty.getId(), op("table.add", Map.of("name", "t_optest_first", "desc", "첫 테이블")));
        ErdTable saved = tableRepository.findByRoomIdAndName(empty.getId(), "t_optest_first").orElseThrow();
        assertThat(saved.getDomainKey()).isEqualTo("etc");
    }

    @Test
    void tableAdd_다른_방의_도메인은_사용하지_않고_보정한다() {
        ErdRoom other = roomRepository.save(new ErdRoom("t_optest_other_room", "tester"));
        LinkedHashMap<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("stat", new SchemaDoc.DomainDef("통계", "#6b7488"));
        schemaService.replaceAll(other.getId(), new SchemaDoc(domains, List.of(), List.of(), Map.of()));
        // roomId 방에는 stat 도메인이 없다 → 첫 도메인(user)으로 보정
        opService.apply(roomId, op("table.add", Map.of("name", "t_optest_x", "domain", "stat", "desc", "x")));
        ErdTable saved = tableRepository.findByRoomIdAndName(roomId, "t_optest_x").orElseThrow();
        assertThat(saved.getDomainKey()).isEqualTo("user");
    }

    @Test
    void tableApply_도메인이_비어있어도_보정해서_반영한다() {
        addTable("t_optest_apply");
        // 편집 폼이 도메인을 빈 값으로 보내도(신규 테이블 초기화 타이밍) 실패하지 않아야 한다
        opService.apply(roomId, op("table.apply", Map.of(
                "oldName", "t_optest_apply",
                "table", List.of("t_optest_apply", "", "설명", false),
                "columns", List.of(List.of("id", "int", "번호", "PK")),
                "relations", List.of())));
        ErdTable saved = tableRepository.findByRoomIdAndName(roomId, "t_optest_apply").orElseThrow();
        assertThat(saved.getDomainKey()).isEqualTo("user");
        assertThat(saved.getDescription()).isEqualTo("설명");
    }

    @Test
    void domainApply_추가와_이름변경을_반영한다() {
        opService.apply(roomId, op("domain.apply", Map.of("domains", List.of(
                List.of("user", "회원", "#4f8cff"),
                List.of("goods", "상품/브랜드", "#37c98b"),
                List.of("", "신규 도메인", "#ff7a7a")))));
        SchemaDoc doc = schemaService.loadDoc(roomId);
        assertThat(doc.domains()).hasSize(3);
        assertThat(doc.domains().get("user").name()).isEqualTo("회원");
        assertThat(doc.domains().values())
                .extracting(SchemaDoc.DomainDef::name)
                .contains("신규 도메인");
    }

    @Test
    void domainApply_삭제된_도메인의_테이블은_첫_도메인으로_옮긴다() {
        addTable("t_optest_move");
        // goods 만 남기면 user 소속 테이블은 goods 로 이동한다
        opService.apply(roomId, op("domain.apply", Map.of("domains", List.of(
                List.of("goods", "상품/브랜드", "#37c98b")))));
        ErdTable saved = tableRepository.findByRoomIdAndName(roomId, "t_optest_move").orElseThrow();
        assertThat(saved.getDomainKey()).isEqualTo("goods");
        assertThat(schemaService.loadDoc(roomId).domains()).containsOnlyKeys("goods");
    }

    @Test
    void domainApply_빈_목록은_거부한다() {
        assertThatThrownBy(() -> opService.apply(roomId, op("domain.apply", Map.of("domains", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 1개");
    }

    @Test
    void domainApply_이름이_비면_거부한다() {
        assertThatThrownBy(() -> opService.apply(roomId, op("domain.apply", Map.of("domains", List.of(
                List.of("user", "", "#4f8cff"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도메인 이름");
    }

    @Test
    void tableAdd_중복_이름이면_거부한다() {
        addTable("t_optest_dup");
        assertThatThrownBy(() -> addTable("t_optest_dup"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 존재");
    }

    @Test
    void tableAdd_없는_도메인이면_거부한다() {
        assertThatThrownBy(() -> opService.apply(roomId,
                op("table.add", Map.of("name", "t_optest_x", "domain", "no_such", "desc", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도메인");
    }

    @Test
    void tableApply_이름변경과_컬럼_관계를_반영한다() {
        addTable("t_optest_child");
        addTable("t_optest_parent");
        opService.apply(roomId, op("table.apply", Map.of(
                "oldName", "t_optest_child",
                "table", List.of("t_optest_renamed", "goods", "설명변경", false),
                "columns", List.of(List.of("id", "int", "PK 컬럼", "PK"), List.of("ref_no", "int", "", "FK")),
                "relations", List.of(List.of("t_optest_renamed", "t_optest_parent", "참조")))));

        ErdTable renamed = tableRepository.findByRoomIdAndName(roomId, "t_optest_renamed").orElseThrow();
        assertThat(renamed.getDomainKey()).isEqualTo("goods");
        assertThat(tableRepository.findByRoomIdAndName(roomId, "t_optest_child")).isEmpty();
        SchemaDoc doc = schemaService.loadDoc(roomId);
        assertThat(doc.columns().get("t_optest_renamed")).hasSize(2);
        assertThat(doc.relations()).contains(List.of("t_optest_renamed", "t_optest_parent", "참조"));
    }

    @Test
    void tableApply_없는_대상_테이블_관계면_거부한다() {
        addTable("t_optest_solo");
        assertThatThrownBy(() -> opService.apply(roomId, op("table.apply", Map.of(
                "oldName", "t_optest_solo",
                "table", List.of("t_optest_solo", "user", "", false),
                "columns", List.of(),
                "relations", List.of(List.of("t_optest_solo", "no_such_table", ""))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 대상");
    }

    @Test
    void tableApply_빈_테이블명이면_거부한다() {
        addTable("t_optest_blank");
        assertThatThrownBy(() -> opService.apply(roomId, op("table.apply", Map.of(
                "oldName", "t_optest_blank",
                "table", List.of("", "user", "", false),
                "columns", List.of(), "relations", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("테이블명");
    }

    @Test
    void tableDelete_테이블과_연결된_관계를_함께_삭제한다() {
        addTable("t_optest_del_c");
        addTable("t_optest_del_p");
        opService.apply(roomId, op("table.apply", Map.of(
                "oldName", "t_optest_del_c",
                "table", List.of("t_optest_del_c", "user", "", false),
                "columns", List.of(),
                "relations", List.of(List.of("t_optest_del_c", "t_optest_del_p", "")))));
        assertThat(schemaService.loadDoc(roomId).relations()).hasSize(1);

        opService.apply(roomId, op("table.delete", Map.of("name", "t_optest_del_p")));

        assertThat(tableRepository.findByRoomIdAndName(roomId, "t_optest_del_p")).isEmpty();
        assertThat(schemaService.loadDoc(roomId).relations()).isEmpty();
    }

    @Test
    void tableMove_좌표를_저장한다() {
        addTable("t_optest_move");
        opService.apply(roomId, op("table.move", Map.of("name", "t_optest_move", "x", 120.5, "y", 300.0)));
        ErdTable moved = tableRepository.findByRoomIdAndName(roomId, "t_optest_move").orElseThrow();
        assertThat(moved.getPosX()).isEqualTo(120.5);
        assertThat(moved.getPosY()).isEqualTo(300.0);
    }

    @Test
    void tableMove_숫자가_아닌_좌표면_거부한다() {
        addTable("t_optest_move_bad");
        assertThatThrownBy(() -> opService.apply(roomId,
                op("table.move", Map.of("name", "t_optest_move_bad", "x", "abc", "y", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("좌표");
    }

    @Test
    void schemaReplace_전체를_교체한다() {
        opService.apply(roomId, op("schema.replace", Map.of("doc", smallDoc())));
        SchemaDoc doc = schemaService.loadDoc(roomId);
        assertThat(doc.tables()).hasSize(2);
        assertThat(doc.domains()).containsOnlyKeys("d1");
    }

    @Test
    void 다른_방의_테이블은_건드리지_않는다() {
        ErdRoom other = roomRepository.save(new ErdRoom("t_optest_room_other", "tester"));
        LinkedHashMap<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("user", new SchemaDoc.DomainDef("회원/인증", "#4f8cff"));
        schemaService.replaceAll(other.getId(),
                new SchemaDoc(domains, List.of(List.of("t_shared_name", "user", "다른 방")), List.of(), Map.of()));

        // 같은 이름의 테이블을 이 방에도 만들 수 있고, 지워도 다른 방은 그대로다
        addTable("t_shared_name");
        opService.apply(roomId, op("table.delete", Map.of("name", "t_shared_name")));

        assertThat(tableRepository.findByRoomIdAndName(roomId, "t_shared_name")).isEmpty();
        assertThat(tableRepository.findByRoomIdAndName(other.getId(), "t_shared_name")).isPresent();
    }

    @Test
    void 알수없는_op는_거부한다() {
        assertThatThrownBy(() -> opService.apply(roomId, op("nope", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 op");
    }

    @Test
    void validateDoc_도메인_없으면_거부한다() {
        SchemaDoc doc = new SchemaDoc(new LinkedHashMap<>(), List.of(), List.of(), Map.of());
        assertThatThrownBy(() -> opService.validateDoc(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domains");
    }

    @Test
    void validateDoc_없는_도메인의_테이블이면_거부한다() {
        SchemaDoc doc = doc(List.of(List.of("t1", "no_such", "")), List.of());
        assertThatThrownBy(() -> opService.validateDoc(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잘못된 테이블");
    }

    @Test
    void validateDoc_테이블명_중복이면_거부한다() {
        SchemaDoc doc = doc(List.of(List.of("t1", "d1", ""), List.of("t1", "d1", "")), List.of());
        assertThatThrownBy(() -> opService.validateDoc(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void validateDoc_없는_테이블을_잇는_관계면_거부한다() {
        SchemaDoc doc = doc(List.of(List.of("t1", "d1", "")), List.of(List.of("t1", "ghost")));
        assertThatThrownBy(() -> opService.validateDoc(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잘못된 관계");
    }

    private SchemaDoc doc(List<List<Object>> tables, List<List<Object>> relations) {
        LinkedHashMap<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("d1", new SchemaDoc.DomainDef("도메인1", "#111111"));
        return new SchemaDoc(domains, tables, relations, Map.of());
    }

    private Map<String, Object> smallDoc() {
        return Map.of(
                "domains", Map.of("d1", Map.of("name", "도메인1", "color", "#111111")),
                "tables", List.of(List.of("s1", "d1", "첫번째"), List.of("s2", "d1", "두번째")),
                "relations", List.of(List.of("s1", "s2", "라벨")),
                "columns", Map.of("s1", List.of(List.of("id", "int", "", "PK"))));
    }
}
