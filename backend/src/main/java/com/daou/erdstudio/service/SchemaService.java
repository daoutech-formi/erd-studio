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
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 방 하나의 전체 스키마 문서 조회/전체 교체를 담당한다. DB가 단일 진실 소스다. */
@Service
public class SchemaService {

    private final ErdDomainRepository domainRepository;
    private final ErdTableRepository tableRepository;
    private final ErdColumnRepository columnRepository;
    private final ErdRelationRepository relationRepository;
    private final ErdMemoRepository memoRepository;
    private final ObjectMapper objectMapper;

    public SchemaService(ErdDomainRepository domainRepository, ErdTableRepository tableRepository,
                         ErdColumnRepository columnRepository, ErdRelationRepository relationRepository,
                         ErdMemoRepository memoRepository, ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.tableRepository = tableRepository;
        this.columnRepository = columnRepository;
        this.relationRepository = relationRepository;
        this.memoRepository = memoRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty(Long roomId) {
        return tableRepository.countByRoomId(roomId) == 0;
    }

    @Transactional(readOnly = true)
    public SchemaDoc loadDoc(Long roomId) {
        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        for (ErdDomain d : domainRepository.findByRoomIdOrderBySortOrderAsc(roomId)) {
            domains.put(d.getKey(), new SchemaDoc.DomainDef(d.getName(), d.getColor()));
        }
        List<ErdTable> tables = tableRepository.findByRoomIdOrderBySortOrderAsc(roomId);
        Map<Long, String> nameById = new HashMap<>();
        List<List<Object>> tableRows = new ArrayList<>();
        for (ErdTable t : tables) {
            nameById.put(t.getId(), t.getName());
            tableRows.add(Arrays.asList(t.getName(), t.getDomainKey(), t.getDescription(),
                    t.isHub(), t.getPosX(), t.getPosY()));
        }
        return new SchemaDoc(domains, tableRows, loadRelationRows(roomId, nameById), loadColumnRows(nameById),
                loadMemoRows(roomId));
    }

    private List<List<Object>> loadMemoRows(Long roomId) {
        List<List<Object>> rows = new ArrayList<>();
        for (ErdMemo m : memoRepository.findByRoomIdOrderBySortOrderAsc(roomId)) {
            List<String> links = readLinks(m.getLinks());
            // [id, text, x, y, color, links?] — 연결 없는 메모는 5요소로 유지해 문서를 가볍게 한다.
            if (links.isEmpty()) {
                rows.add(Arrays.asList(m.getMemoKey(), m.getText(), m.getPosX(), m.getPosY(), m.getColor()));
            } else {
                rows.add(Arrays.asList(m.getMemoKey(), m.getText(), m.getPosX(), m.getPosY(), m.getColor(), links));
            }
        }
        return rows;
    }

    private List<String> readLinks(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<List<Object>> loadRelationRows(Long roomId, Map<Long, String> nameById) {
        List<List<Object>> rows = new ArrayList<>();
        for (ErdRelation r : relationRepository.findByRoomIdOrderBySortOrderAsc(roomId)) {
            String child = nameById.get(r.getChildTableId());
            String parent = nameById.get(r.getParentTableId());
            if (child == null || parent == null) {
                continue;
            }
            // [child, parent, label?, cardinality?] — 뒤쪽 빈 값은 생략해 문서를 가볍게 유지한다.
            if (!r.getCardinality().isEmpty()) {
                rows.add(List.of(child, parent, r.getLabel(), r.getCardinality()));
            } else if (!r.getLabel().isEmpty()) {
                rows.add(List.of(child, parent, r.getLabel()));
            } else {
                rows.add(List.of(child, parent));
            }
        }
        return rows;
    }

    /** 테이블이 없으면 `in ()` 이 되어 버리므로 질의를 건너뛴다. */
    private Map<String, List<List<Object>>> loadColumnRows(Map<Long, String> nameById) {
        Map<String, List<List<Object>>> columns = new LinkedHashMap<>();
        if (nameById.isEmpty()) {
            return columns;
        }
        for (ErdColumn c : columnRepository.findByTableIds(nameById.keySet())) {
            String tableName = nameById.get(c.getTableId());
            if (tableName == null) {
                continue;
            }
            List<Object> row = c.getFlag().isEmpty()
                    ? List.of(c.getName(), c.getColType(), c.getComment())
                    : List.of(c.getName(), c.getColType(), c.getComment(), c.getFlag());
            columns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(row);
        }
        return columns;
    }

    /** 방 하나의 문서 전체를 트랜잭션으로 교체한다 (시드 적재·JSON 불러오기·이력 복원용). */
    @Transactional
    public void replaceAll(Long roomId, SchemaDoc doc) {
        deleteAll(roomId);
        insertDomains(roomId, doc);
        Map<String, Long> tableIds = insertTables(roomId, doc);
        insertColumns(doc, tableIds);
        insertRelations(roomId, doc, tableIds);
        insertMemos(roomId, doc);
    }

    /** 방의 스키마 데이터(관계·컬럼·테이블·도메인)를 모두 지운다. 이력은 남긴다. */
    @Transactional
    public void deleteAll(Long roomId) {
        List<Long> tableIds = tableRepository.findByRoomIdOrderBySortOrderAsc(roomId).stream()
                .map(ErdTable::getId)
                .toList();
        relationRepository.deleteByRoomId(roomId);
        if (!tableIds.isEmpty()) {
            columnRepository.deleteByTableIds(tableIds);
        }
        tableRepository.deleteByRoomId(roomId);
        domainRepository.deleteByRoomId(roomId);
        memoRepository.deleteByRoomId(roomId);
    }

    private void insertDomains(Long roomId, SchemaDoc doc) {
        List<ErdDomain> entities = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, SchemaDoc.DomainDef> e : doc.domains().entrySet()) {
            entities.add(new ErdDomain(roomId, e.getKey(), e.getValue().name(), e.getValue().color(), order++));
        }
        domainRepository.saveAll(entities);
    }

    private Map<String, Long> insertTables(Long roomId, SchemaDoc doc) {
        List<ErdTable> entities = new ArrayList<>();
        for (int i = 0; i < doc.tables().size(); i++) {
            List<Object> row = doc.tables().get(i);
            entities.add(new ErdTable(roomId, Rows.str(row, 0), Rows.str(row, 1), Rows.str(row, 2),
                    Rows.bool(row, 3), i, Rows.dbl(row, 4), Rows.dbl(row, 5)));
        }
        Map<String, Long> ids = new HashMap<>();
        for (ErdTable saved : tableRepository.saveAll(entities)) {
            ids.put(saved.getName(), saved.getId());
        }
        return ids;
    }

    private void insertColumns(SchemaDoc doc, Map<String, Long> tableIds) {
        List<ErdColumn> entities = new ArrayList<>();
        for (Map.Entry<String, List<List<Object>>> e : doc.columns().entrySet()) {
            Long tableId = tableIds.get(e.getKey());
            if (tableId == null) {
                continue;
            }
            List<List<Object>> rows = e.getValue();
            for (int i = 0; i < rows.size(); i++) {
                List<Object> row = rows.get(i);
                entities.add(new ErdColumn(tableId, Rows.str(row, 0), Rows.str(row, 1),
                        Rows.str(row, 2), Rows.str(row, 3), i));
            }
        }
        columnRepository.saveAll(entities);
    }

    private void insertRelations(Long roomId, SchemaDoc doc, Map<String, Long> tableIds) {
        List<ErdRelation> entities = new ArrayList<>();
        for (int i = 0; i < doc.relations().size(); i++) {
            List<Object> row = doc.relations().get(i);
            Long childId = tableIds.get(Rows.str(row, 0));
            Long parentId = tableIds.get(Rows.str(row, 1));
            if (childId == null || parentId == null) {
                continue;
            }
            entities.add(new ErdRelation(roomId, childId, parentId, Rows.str(row, 2), Rows.str(row, 3), i));
        }
        relationRepository.saveAll(entities);
    }

    private void insertMemos(Long roomId, SchemaDoc doc) {
        Set<String> tableNames = new HashSet<>();
        for (List<Object> row : doc.tables()) {
            tableNames.add(Rows.str(row, 0));
        }
        List<ErdMemo> entities = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Object> row : doc.memos()) {
            String key = Rows.str(row, 0);
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            Double x = Rows.dbl(row, 2);
            Double y = Rows.dbl(row, 3);
            List<String> links = Rows.strList(row, 5).stream().filter(tableNames::contains).toList();
            entities.add(new ErdMemo(roomId, key, Rows.str(row, 1), Rows.str(row, 4),
                    x == null ? 0 : x, y == null ? 0 : y, entities.size(), writeLinks(links)));
        }
        memoRepository.saveAll(entities);
    }

    private String writeLinks(List<String> links) {
        if (links.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(links);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
