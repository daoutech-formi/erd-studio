package com.daou.erdstudio.service;

import com.daou.erdstudio.service.DdlParser.ParsedColumn;
import com.daou.erdstudio.service.DdlParser.ParsedRelation;
import com.daou.erdstudio.service.DdlParser.ParsedSchema;
import com.daou.erdstudio.service.DdlParser.ParsedTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Smart Query 임포트 — 추출 쿼리(information_schema/pg_catalog 조회)가 출력한 JSON 을
 * DDL 파서와 동일한 {@link ParsedSchema} 로 변환한다. 이후 병합·도메인 분류·관계 추론은
 * {@link DdlImportService} 파이프라인을 그대로 탄다.
 *
 * 기대 형식:
 * { "tables": [ { "name", "comment", "columns": [ { "name", "type", "comment", "pk", "uk" } ] } ],
 *   "relations": [ { "child", "childCol", "parent", "parentCol" } ] }
 * pk/uk 는 true/false 외에 1/0(MySQL JSON_OBJECT 결과)도 허용한다.
 */
@Component
public class SmartQueryParser {

    private final ObjectMapper objectMapper;

    public SmartQueryParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedSchema parse(String json) {
        JsonNode root = readRoot(json);
        JsonNode tablesNode = root.path("tables");
        if (!tablesNode.isArray() || tablesNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "tables 배열을 찾지 못했습니다. 추출 쿼리 결과의 JSON 값 전체를 붙여넣었는지 확인하세요.");
        }

        List<ParsedTable> tables = new ArrayList<>();
        for (JsonNode t : tablesNode) {
            String name = t.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            List<ParsedColumn> columns = new ArrayList<>();
            for (JsonNode c : t.path("columns")) {
                ParsedColumn col = parseColumn(c);
                if (col != null) {
                    columns.add(col);
                }
            }
            tables.add(new ParsedTable(name, t.path("comment").asText(""), columns));
        }
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("이름 있는 테이블이 하나도 없습니다.");
        }

        List<ParsedRelation> relations = parseRelations(root.path("relations"));
        applyFkFlags(tables, relations);
        return new ParsedSchema(tables, relations);
    }

    /** 루트 파싱 — DB 클라이언트가 셀 값을 문자열로 한 번 감싼 경우("{"…"}")도 받아들인다. */
    private JsonNode readRoot(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON 내용이 비어 있습니다.");
        }
        try {
            JsonNode root = objectMapper.readTree(json.trim());
            if (root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            if (!root.isObject()) {
                throw new IllegalArgumentException("JSON 객체가 아닙니다. 추출 쿼리 결과를 그대로 붙여넣으세요.");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 파싱 실패: " + e.getOriginalMessage());
        }
    }

    private ParsedColumn parseColumn(JsonNode c) {
        String name = c.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }
        String rawType = c.path("type").asText("");
        boolean unsigned = rawType.toLowerCase(Locale.ROOT).contains("unsigned");
        String type = DdlParser.normalizeType(
                rawType.replaceAll("(?i)\\s*unsigned", "").trim(), unsigned);
        Set<String> flags = new LinkedHashSet<>();
        if (c.path("pk").asBoolean(false)) {
            flags.add("PK");
        } else if (c.path("uk").asBoolean(false)) {
            flags.add("UK");
        }
        return new ParsedColumn(name, type, c.path("comment").asText(""), flags);
    }

    private List<ParsedRelation> parseRelations(JsonNode relationsNode) {
        List<ParsedRelation> relations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode r : relationsNode) {
            String child = r.path("child").asText("");
            String parent = r.path("parent").asText("");
            if (child.isBlank() || parent.isBlank()) {
                continue;
            }
            String parentCol = r.path("parentCol").asText("");
            String childCol = r.path("childCol").asText(parentCol);
            if (seen.add(child + "→" + parent + "." + childCol)) {
                relations.add(new ParsedRelation(child, parent, childCol, parentCol));
            }
        }
        return relations;
    }

    private void applyFkFlags(List<ParsedTable> tables, List<ParsedRelation> relations) {
        for (ParsedRelation r : relations) {
            tables.stream()
                    .filter(t -> t.name().equals(r.child()))
                    .flatMap(t -> t.columns().stream())
                    .filter(c -> c.name().equals(r.childCol()))
                    .forEach(c -> c.flags().add("FK"));
        }
    }
}
