package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdColumn;
import com.daou.erdstudio.domain.ErdRelation;
import com.daou.erdstudio.domain.ErdTable;
import com.daou.erdstudio.repository.ErdColumnRepository;
import com.daou.erdstudio.repository.ErdDomainRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 편집 op의 검증·DB 반영·이력 기록. 실패 시 IllegalArgumentException(한국어 메시지)을 던진다. */
@Service
public class OpService {

    private final ErdDomainRepository domainRepository;
    private final ErdTableRepository tableRepository;
    private final ErdColumnRepository columnRepository;
    private final ErdRelationRepository relationRepository;
    private final SchemaService schemaService;
    private final HistoryRecorder historyRecorder;
    private final ObjectMapper objectMapper;

    public OpService(ErdDomainRepository domainRepository, ErdTableRepository tableRepository,
                     ErdColumnRepository columnRepository, ErdRelationRepository relationRepository,
                     SchemaService schemaService, HistoryRecorder historyRecorder, ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.tableRepository = tableRepository;
        this.columnRepository = columnRepository;
        this.relationRepository = relationRepository;
        this.schemaService = schemaService;
        this.historyRecorder = historyRecorder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(Op op) {
        JsonNode p = op.payload();
        String target = switch (op.type()) {
            case "table.add" -> applyTableAdd(p);
            case "table.apply" -> applyTableApply(p);
            case "table.delete" -> applyTableDelete(p);
            case "table.move" -> applyTableMove(p);
            case "schema.replace" -> applySchemaReplace(p);
            default -> throw new IllegalArgumentException("알 수 없는 op 유형: " + op.type());
        };
        historyRecorder.record(op.user(), op.type(), target, op);
    }

    private String applyTableAdd(JsonNode p) {
        String name = p.path("name").asText("").trim();
        String domain = p.path("domain").asText("");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("테이블명을 입력하세요.");
        }
        if (tableRepository.existsByName(name)) {
            throw new IllegalArgumentException("이미 존재하는 테이블명입니다: " + name);
        }
        requireDomain(domain);
        int order = (int) tableRepository.count();
        tableRepository.save(new ErdTable(name, domain, p.path("desc").asText(""), false, order, null, null));
        return name;
    }

    private String applyTableApply(JsonNode p) {
        String oldName = p.path("oldName").asText("");
        ErdTable table = findTable(oldName);
        List<Object> row = toRow(p.path("table"));
        String newName = Rows.str(row, 0).trim();
        if (newName.isEmpty()) {
            throw new IllegalArgumentException("테이블명을 입력하세요.");
        }
        if (!newName.equals(oldName) && tableRepository.existsByName(newName)) {
            throw new IllegalArgumentException("이미 존재하는 테이블명입니다: " + newName);
        }
        requireDomain(Rows.str(row, 1));
        table.rename(newName);
        table.update(Rows.str(row, 1), Rows.str(row, 2), Rows.bool(row, 3));
        replaceColumns(table, toRows(p.path("columns")));
        replaceChildRelations(table, toRows(p.path("relations")));
        return newName;
    }

    private void replaceColumns(ErdTable table, List<List<Object>> rows) {
        columnRepository.deleteByTableId(table.getId());
        int order = 0;
        for (List<Object> row : rows) {
            String name = Rows.str(row, 0).trim();
            if (name.isEmpty()) {
                continue;
            }
            columnRepository.save(new ErdColumn(table.getId(), name, Rows.str(row, 1),
                    Rows.str(row, 2), Rows.str(row, 3), order++));
        }
    }

    private void replaceChildRelations(ErdTable table, List<List<Object>> rows) {
        relationRepository.deleteByChildTableId(table.getId());
        int order = 0;
        for (List<Object> row : rows) {
            String parentName = Rows.str(row, 1);
            ErdTable parent = tableRepository.findByName(parentName).orElseThrow(
                    () -> new IllegalArgumentException("존재하지 않는 대상 테이블입니다: " + parentName));
            relationRepository.save(new ErdRelation(table.getId(), parent.getId(), Rows.str(row, 2), order++));
        }
    }

    private String applyTableDelete(JsonNode p) {
        String name = p.path("name").asText("");
        ErdTable table = findTable(name);
        columnRepository.deleteByTableId(table.getId());
        relationRepository.deleteAllInvolving(table.getId());
        tableRepository.delete(table);
        return name;
    }

    private String applyTableMove(JsonNode p) {
        String name = p.path("name").asText("");
        ErdTable table = findTable(name);
        if (!p.path("x").isNumber() || !p.path("y").isNumber()) {
            throw new IllegalArgumentException("좌표가 올바르지 않습니다.");
        }
        table.moveTo(p.path("x").asDouble(), p.path("y").asDouble());
        return name;
    }

    private String applySchemaReplace(JsonNode p) {
        SchemaDoc doc = objectMapper.convertValue(p.path("doc"), SchemaDoc.class);
        validateDoc(doc);
        schemaService.replaceAll(doc);
        return "전체 스키마";
    }

    /** 전체 문서 유효성 검증 — 구 서버(validateDoc)와 동일 규칙. */
    public void validateDoc(SchemaDoc doc) {
        if (doc == null || doc.domains() == null || doc.domains().isEmpty()) {
            throw new IllegalArgumentException("domains가 없습니다.");
        }
        if (doc.tables() == null || doc.relations() == null || doc.columns() == null) {
            throw new IllegalArgumentException("tables/relations/columns가 올바르지 않습니다.");
        }
        Set<String> names = new HashSet<>();
        for (List<Object> row : doc.tables()) {
            String name = Rows.str(row, 0);
            if (name.isEmpty() || !doc.domains().containsKey(Rows.str(row, 1))) {
                throw new IllegalArgumentException("잘못된 테이블 항목: " + row);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("테이블명이 중복되었습니다: " + name);
            }
        }
        for (List<Object> row : doc.relations()) {
            if (!names.contains(Rows.str(row, 0)) || !names.contains(Rows.str(row, 1))) {
                throw new IllegalArgumentException("잘못된 관계 항목: " + row);
            }
        }
    }

    private ErdTable findTable(String name) {
        return tableRepository.findByName(name).orElseThrow(
                () -> new IllegalArgumentException("존재하지 않는 테이블입니다: " + name));
    }

    private void requireDomain(String key) {
        if (!domainRepository.existsById(key)) {
            throw new IllegalArgumentException("존재하지 않는 도메인입니다: " + key);
        }
    }

    private List<Object> toRow(JsonNode node) {
        return objectMapper.convertValue(node, new TypeReference<List<Object>>() {
        });
    }

    private List<List<Object>> toRows(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<List<Object>>>() {
        });
    }
}
