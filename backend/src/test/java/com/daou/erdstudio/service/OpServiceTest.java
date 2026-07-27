package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdTable;
import com.daou.erdstudio.repository.ErdColumnRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    ErdColumnRepository columnRepository;
    @Autowired
    ErdRelationRepository relationRepository;
    @Autowired
    SchemaService schemaService;
    @Autowired
    ObjectMapper objectMapper;

    private Op op(String type, Map<String, Object> payload) {
        return new Op(type, "tester", objectMapper.valueToTree(payload));
    }

    private void addTable(String name) {
        opService.apply(op("table.add", Map.of("name", name, "domain", "user", "desc", "테스트")));
    }

    @Test
    void tableAdd_새_테이블을_생성한다() {
        addTable("t_optest_a");
        ErdTable saved = tableRepository.findByName("t_optest_a").orElseThrow();
        assertThat(saved.getDomainKey()).isEqualTo("user");
        assertThat(saved.getDescription()).isEqualTo("테스트");
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
        assertThatThrownBy(() -> opService.apply(
                op("table.add", Map.of("name", "t_optest_x", "domain", "no_such", "desc", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도메인");
    }

    @Test
    void tableApply_이름변경과_컬럼_관계를_반영한다() {
        addTable("t_optest_child");
        addTable("t_optest_parent");
        opService.apply(op("table.apply", Map.of(
                "oldName", "t_optest_child",
                "table", List.of("t_optest_renamed", "goods", "설명변경", false),
                "columns", List.of(List.of("id", "int", "PK 컬럼", "PK"), List.of("ref_no", "int", "", "FK")),
                "relations", List.of(List.of("t_optest_renamed", "t_optest_parent", "참조")))));

        ErdTable renamed = tableRepository.findByName("t_optest_renamed").orElseThrow();
        assertThat(renamed.getDomainKey()).isEqualTo("goods");
        assertThat(tableRepository.findByName("t_optest_child")).isEmpty();
        SchemaDoc doc = schemaService.loadDoc();
        assertThat(doc.columns().get("t_optest_renamed")).hasSize(2);
        assertThat(doc.relations()).contains(List.of("t_optest_renamed", "t_optest_parent", "참조"));
    }

    @Test
    void tableApply_없는_대상_테이블_관계면_거부한다() {
        addTable("t_optest_solo");
        assertThatThrownBy(() -> opService.apply(op("table.apply", Map.of(
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
        assertThatThrownBy(() -> opService.apply(op("table.apply", Map.of(
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
        opService.apply(op("table.apply", Map.of(
                "oldName", "t_optest_del_c",
                "table", List.of("t_optest_del_c", "user", "", false),
                "columns", List.of(),
                "relations", List.of(List.of("t_optest_del_c", "t_optest_del_p", "")))));
        long relationsBefore = relationRepository.count();

        opService.apply(op("table.delete", Map.of("name", "t_optest_del_p")));

        assertThat(tableRepository.findByName("t_optest_del_p")).isEmpty();
        assertThat(relationRepository.count()).isEqualTo(relationsBefore - 1);
    }

    @Test
    void tableMove_좌표를_저장한다() {
        addTable("t_optest_move");
        opService.apply(op("table.move", Map.of("name", "t_optest_move", "x", 120.5, "y", 300.0)));
        ErdTable moved = tableRepository.findByName("t_optest_move").orElseThrow();
        assertThat(moved.getPosX()).isEqualTo(120.5);
        assertThat(moved.getPosY()).isEqualTo(300.0);
    }

    @Test
    void tableMove_숫자가_아닌_좌표면_거부한다() {
        addTable("t_optest_move_bad");
        assertThatThrownBy(() -> opService.apply(
                op("table.move", Map.of("name", "t_optest_move_bad", "x", "abc", "y", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("좌표");
    }

    @Test
    void schemaReplace_전체를_교체한다() {
        opService.apply(op("schema.replace", Map.of("doc", smallDoc())));
        SchemaDoc doc = schemaService.loadDoc();
        assertThat(doc.tables()).hasSize(2);
        assertThat(doc.domains()).containsOnlyKeys("d1");
    }

    @Test
    void 알수없는_op는_거부한다() {
        assertThatThrownBy(() -> opService.apply(op("nope", Map.of())))
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
