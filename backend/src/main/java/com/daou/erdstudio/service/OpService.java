package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdColumn;
import com.daou.erdstudio.domain.ErdDomain;
import com.daou.erdstudio.domain.ErdMemo;
import com.daou.erdstudio.domain.ErdRelation;
import com.daou.erdstudio.domain.ErdTable;
import com.daou.erdstudio.repository.ErdColumnRepository;
import com.daou.erdstudio.repository.ErdDomainRepository;
import com.daou.erdstudio.repository.ErdMemoRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 편집 op의 검증·DB 반영·이력 기록. 모든 반영은 방(roomId) 범위 안에서만 이뤄진다.
 * 실패 시 IllegalArgumentException(한국어 메시지)을 던진다.
 */
@Service
public class OpService {

    /** 방에 도메인이 하나도 없을 때 자동으로 만들어 주는 기본 도메인. */
    private static final String DEFAULT_DOMAIN_KEY = "etc";
    private static final String DEFAULT_DOMAIN_NAME = "기타";
    private static final String DEFAULT_DOMAIN_COLOR = "#9aa0aa";
    /** 한 방에서 만들 수 있는 도메인 최대 개수 (범례 가독성 상한). */
    private static final int MAX_DOMAINS = 20;
    /** 한 방에서 만들 수 있는 메모 최대 개수. */
    private static final int MAX_MEMOS = 200;
    private static final int MAX_MEMO_KEY_LENGTH = 64;
    private static final int MAX_MEMO_TEXT_LENGTH = 500;

    private final ErdDomainRepository domainRepository;
    private final ErdTableRepository tableRepository;
    private final ErdColumnRepository columnRepository;
    private final ErdRelationRepository relationRepository;
    private final ErdMemoRepository memoRepository;
    private final SchemaService schemaService;
    private final HistoryRecorder historyRecorder;
    private final ObjectMapper objectMapper;

    public OpService(ErdDomainRepository domainRepository, ErdTableRepository tableRepository,
                     ErdColumnRepository columnRepository, ErdRelationRepository relationRepository,
                     ErdMemoRepository memoRepository, SchemaService schemaService,
                     HistoryRecorder historyRecorder, ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.tableRepository = tableRepository;
        this.columnRepository = columnRepository;
        this.relationRepository = relationRepository;
        this.memoRepository = memoRepository;
        this.schemaService = schemaService;
        this.historyRecorder = historyRecorder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(Long roomId, Op op) {
        JsonNode p = op.payload();
        String target = switch (op.type()) {
            case "table.add" -> applyTableAdd(roomId, p);
            case "table.apply" -> applyTableApply(roomId, p);
            case "table.delete" -> applyTableDelete(roomId, p);
            case "table.move" -> applyTableMove(roomId, p);
            case "domain.apply" -> applyDomainApply(roomId, p);
            case "memo.add" -> applyMemoAdd(roomId, p);
            case "memo.apply" -> applyMemoApply(roomId, p);
            case "memo.delete" -> applyMemoDelete(roomId, p);
            case "memo.move" -> applyMemoMove(roomId, p);
            case "schema.replace" -> applySchemaReplace(roomId, p);
            default -> throw new IllegalArgumentException("알 수 없는 op 유형: " + op.type());
        };
        historyRecorder.record(roomId, op.user(), op.type(), target, op);
    }

    private String applyTableAdd(Long roomId, JsonNode p) {
        String name = p.path("name").asText("").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("테이블명을 입력하세요.");
        }
        if (tableRepository.existsByRoomIdAndName(roomId, name)) {
            throw new IllegalArgumentException("이미 존재하는 테이블명입니다: " + name);
        }
        String domain = resolveDomain(roomId, p.path("domain").asText(""));
        int order = (int) tableRepository.countByRoomId(roomId);
        tableRepository.save(new ErdTable(roomId, name, domain, p.path("desc").asText(""), false, order, null, null));
        return name;
    }

    private String applyTableApply(Long roomId, JsonNode p) {
        String oldName = p.path("oldName").asText("");
        ErdTable table = findTable(roomId, oldName);
        List<Object> row = toRow(p.path("table"));
        String newName = Rows.str(row, 0).trim();
        if (newName.isEmpty()) {
            throw new IllegalArgumentException("테이블명을 입력하세요.");
        }
        if (!newName.equals(oldName) && tableRepository.existsByRoomIdAndName(roomId, newName)) {
            throw new IllegalArgumentException("이미 존재하는 테이블명입니다: " + newName);
        }
        table.rename(newName);
        table.update(resolveDomain(roomId, Rows.str(row, 1)), Rows.str(row, 2), Rows.bool(row, 3));
        replaceColumns(table, toRows(p.path("columns")));
        replaceChildRelations(roomId, table, toRows(p.path("relations")));
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

    private void replaceChildRelations(Long roomId, ErdTable table, List<List<Object>> rows) {
        relationRepository.deleteByChildTableId(table.getId());
        int order = 0;
        for (List<Object> row : rows) {
            String parentName = Rows.str(row, 1);
            ErdTable parent = tableRepository.findByRoomIdAndName(roomId, parentName).orElseThrow(
                    () -> new IllegalArgumentException("존재하지 않는 대상 테이블입니다: " + parentName));
            relationRepository.save(new ErdRelation(roomId, table.getId(), parent.getId(),
                    Rows.str(row, 2), Rows.str(row, 3), order++));
        }
    }

    private String applyTableDelete(Long roomId, JsonNode p) {
        String name = p.path("name").asText("");
        ErdTable table = findTable(roomId, name);
        columnRepository.deleteByTableId(table.getId());
        relationRepository.deleteAllInvolving(table.getId());
        tableRepository.delete(table);
        return name;
    }

    private String applyTableMove(Long roomId, JsonNode p) {
        String name = p.path("name").asText("");
        ErdTable table = findTable(roomId, name);
        if (!p.path("x").isNumber() || !p.path("y").isNumber()) {
            throw new IllegalArgumentException("좌표가 올바르지 않습니다.");
        }
        table.moveTo(p.path("x").asDouble(), p.path("y").asDouble());
        return name;
    }

    private String applyMemoAdd(Long roomId, JsonNode p) {
        String key = p.path("id").asText("").trim();
        if (key.isEmpty() || key.length() > MAX_MEMO_KEY_LENGTH) {
            throw new IllegalArgumentException("메모 식별자가 올바르지 않습니다.");
        }
        if (memoRepository.existsByRoomIdAndMemoKey(roomId, key)) {
            throw new IllegalArgumentException("이미 존재하는 메모입니다: " + key);
        }
        if (memoRepository.countByRoomId(roomId) >= MAX_MEMOS) {
            throw new IllegalArgumentException("메모는 방마다 최대 " + MAX_MEMOS + "개까지 만들 수 있습니다.");
        }
        if (!p.path("x").isNumber() || !p.path("y").isNumber()) {
            throw new IllegalArgumentException("좌표가 올바르지 않습니다.");
        }
        String text = memoText(p);
        int order = (int) memoRepository.countByRoomId(roomId);
        memoRepository.save(new ErdMemo(roomId, key, text, memoColor(p),
                p.path("x").asDouble(), p.path("y").asDouble(), order));
        return memoTarget(text);
    }

    private String applyMemoApply(Long roomId, JsonNode p) {
        ErdMemo memo = findMemo(roomId, p);
        memo.update(memoText(p), memoColor(p));
        return memoTarget(memo.getText());
    }

    private String applyMemoDelete(Long roomId, JsonNode p) {
        ErdMemo memo = findMemo(roomId, p);
        memoRepository.delete(memo);
        return memoTarget(memo.getText());
    }

    private String applyMemoMove(Long roomId, JsonNode p) {
        ErdMemo memo = findMemo(roomId, p);
        if (!p.path("x").isNumber() || !p.path("y").isNumber()) {
            throw new IllegalArgumentException("좌표가 올바르지 않습니다.");
        }
        memo.moveTo(p.path("x").asDouble(), p.path("y").asDouble());
        return memoTarget(memo.getText());
    }

    private ErdMemo findMemo(Long roomId, JsonNode p) {
        String key = p.path("id").asText("");
        return memoRepository.findByRoomIdAndMemoKey(roomId, key).orElseThrow(
                () -> new IllegalArgumentException("존재하지 않는 메모입니다: " + key));
    }

    private String memoText(JsonNode p) {
        String text = p.path("text").asText("");
        if (text.length() > MAX_MEMO_TEXT_LENGTH) {
            throw new IllegalArgumentException("메모는 최대 " + MAX_MEMO_TEXT_LENGTH + "자까지 입력할 수 있습니다.");
        }
        return text;
    }

    private String memoColor(JsonNode p) {
        String color = p.path("color").asText("").trim();
        if (color.length() > 32) {
            throw new IllegalArgumentException("메모 색상이 올바르지 않습니다.");
        }
        return color;
    }

    /** 이력 목록에 표시할 메모 대상명 — 첫 줄 앞부분, 비어 있으면 '메모'. */
    private String memoTarget(String text) {
        String head = text.strip().split("\n", 2)[0];
        if (head.isEmpty()) {
            return "메모";
        }
        return head.length() > 20 ? head.substring(0, 20) + "…" : head;
    }

    private String applySchemaReplace(Long roomId, JsonNode p) {
        SchemaDoc doc = objectMapper.convertValue(p.path("doc"), SchemaDoc.class);
        validateDoc(doc);
        schemaService.replaceAll(roomId, doc);
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
        Set<String> memoKeys = new HashSet<>();
        for (List<Object> row : doc.memos()) {
            String key = Rows.str(row, 0);
            if (key.isEmpty() || key.length() > MAX_MEMO_KEY_LENGTH) {
                throw new IllegalArgumentException("잘못된 메모 항목: " + row);
            }
            if (!memoKeys.add(key)) {
                throw new IllegalArgumentException("메모 식별자가 중복되었습니다: " + key);
            }
        }
    }

    private ErdTable findTable(Long roomId, String name) {
        return tableRepository.findByRoomIdAndName(roomId, name).orElseThrow(
                () -> new IllegalArgumentException("존재하지 않는 테이블입니다: " + name));
    }

    /**
     * 도메인 목록 전체 교체 — 추가·이름/색상 변경·삭제·순서 변경을 한 번에 반영한다.
     * payload: {domains: [[key, name, color], ...]} (key 가 비면 새 도메인으로 보고 서버가 키를 만든다)
     * 삭제된 도메인에 속해 있던 테이블은 목록의 첫 도메인으로 옮긴다.
     */
    private String applyDomainApply(Long roomId, JsonNode p) {
        List<List<Object>> rows = toRows(p.path("domains"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("도메인은 최소 1개 이상이어야 합니다.");
        }
        if (rows.size() > MAX_DOMAINS) {
            throw new IllegalArgumentException("도메인은 최대 " + MAX_DOMAINS + "개까지 만들 수 있습니다.");
        }
        List<ErdDomain> existing = domainRepository.findByRoomIdOrderBySortOrderAsc(roomId);
        Map<String, ErdDomain> byKey = new LinkedHashMap<>();
        existing.forEach(d -> byKey.put(d.getKey(), d));

        Set<String> keptKeys = new LinkedHashSet<>();
        List<String> orderedKeys = new ArrayList<>();
        int order = 0;
        for (List<Object> row : rows) {
            String key = Rows.str(row, 0).trim();
            String name = Rows.str(row, 1).trim();
            String color = Rows.str(row, 2).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("도메인 이름을 입력하세요.");
            }
            if (color.isEmpty()) {
                color = DEFAULT_DOMAIN_COLOR;
            }
            if (key.isEmpty()) {
                key = nextDomainKey(keptKeys, byKey.keySet());
            }
            if (!keptKeys.add(key)) {
                throw new IllegalArgumentException("도메인이 중복되었습니다: " + key);
            }
            ErdDomain found = byKey.get(key);
            if (found != null) {
                found.update(name, color, order);
            } else {
                domainRepository.save(new ErdDomain(roomId, key, name, color, order));
            }
            orderedKeys.add(key);
            order++;
        }

        String fallback = orderedKeys.get(0);
        List<ErdDomain> removed = existing.stream().filter(d -> !keptKeys.contains(d.getKey())).toList();
        if (!removed.isEmpty()) {
            Set<String> removedKeys = new HashSet<>();
            removed.forEach(d -> removedKeys.add(d.getKey()));
            for (ErdTable t : tableRepository.findByRoomIdOrderBySortOrderAsc(roomId)) {
                if (removedKeys.contains(t.getDomainKey())) {
                    t.update(fallback, t.getDescription(), t.isHub());
                }
            }
            removed.forEach(domainRepository::delete);
        }
        return "도메인";
    }

    /** 새 도메인 키 생성 — d1, d2 … 중 아직 쓰이지 않은 값. */
    private String nextDomainKey(Set<String> taken, Set<String> existing) {
        for (int i = 1; i <= MAX_DOMAINS * 2; i++) {
            String candidate = "d" + i;
            if (!taken.contains(candidate) && !existing.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("도메인 키를 만들 수 없습니다.");
    }

    /**
     * 도메인 보정 — 클라이언트가 방의 도메인 키를 몰라도 되도록 서버가 정한다.
     * 요청한 키가 있으면 그대로, 없으면 방의 첫 도메인, 방에 도메인이 하나도 없으면 기본 도메인을 만든다.
     */
    private String resolveDomain(Long roomId, String requested) {
        if (!requested.isBlank() && domainRepository.existsByRoomIdAndKey(roomId, requested)) {
            return requested;
        }
        return domainRepository.findByRoomIdOrderBySortOrderAsc(roomId).stream()
                .findFirst()
                .map(ErdDomain::getKey)
                .orElseGet(() -> createDefaultDomain(roomId));
    }

    /** 도메인이 없는 방(구버전에서 만들어진 방 등)을 위해 '기타' 도메인을 만들어 준다. */
    private String createDefaultDomain(Long roomId) {
        domainRepository.save(new ErdDomain(roomId, DEFAULT_DOMAIN_KEY, DEFAULT_DOMAIN_NAME, DEFAULT_DOMAIN_COLOR, 0));
        return DEFAULT_DOMAIN_KEY;
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
