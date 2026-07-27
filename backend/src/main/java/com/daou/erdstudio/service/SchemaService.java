package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdColumn;
import com.daou.erdstudio.domain.ErdDomain;
import com.daou.erdstudio.domain.ErdRelation;
import com.daou.erdstudio.domain.ErdTable;
import com.daou.erdstudio.repository.ErdColumnRepository;
import com.daou.erdstudio.repository.ErdDomainRepository;
import com.daou.erdstudio.repository.ErdRelationRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.web.dto.SchemaDoc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 전체 스키마 문서의 조회/전체 교체를 담당한다. DB가 단일 진실 소스다. */
@Service
public class SchemaService {

    private final ErdDomainRepository domainRepository;
    private final ErdTableRepository tableRepository;
    private final ErdColumnRepository columnRepository;
    private final ErdRelationRepository relationRepository;

    public SchemaService(ErdDomainRepository domainRepository, ErdTableRepository tableRepository,
                         ErdColumnRepository columnRepository, ErdRelationRepository relationRepository) {
        this.domainRepository = domainRepository;
        this.tableRepository = tableRepository;
        this.columnRepository = columnRepository;
        this.relationRepository = relationRepository;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return tableRepository.count() == 0;
    }

    @Transactional(readOnly = true)
    public SchemaDoc loadDoc() {
        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        for (ErdDomain d : domainRepository.findAllByOrderBySortOrderAsc()) {
            domains.put(d.getKey(), new SchemaDoc.DomainDef(d.getName(), d.getColor()));
        }
        List<ErdTable> tables = tableRepository.findAllByOrderBySortOrderAsc();
        Map<Long, String> nameById = new HashMap<>();
        List<List<Object>> tableRows = new ArrayList<>();
        for (ErdTable t : tables) {
            nameById.put(t.getId(), t.getName());
            tableRows.add(Arrays.asList(t.getName(), t.getDomainKey(), t.getDescription(),
                    t.isHub(), t.getPosX(), t.getPosY()));
        }
        return new SchemaDoc(domains, tableRows, loadRelationRows(nameById), loadColumnRows(nameById));
    }

    private List<List<Object>> loadRelationRows(Map<Long, String> nameById) {
        List<List<Object>> rows = new ArrayList<>();
        for (ErdRelation r : relationRepository.findAllByOrderBySortOrderAsc()) {
            String child = nameById.get(r.getChildTableId());
            String parent = nameById.get(r.getParentTableId());
            if (child == null || parent == null) {
                continue;
            }
            rows.add(r.getLabel().isEmpty() ? List.of(child, parent) : List.of(child, parent, r.getLabel()));
        }
        return rows;
    }

    private Map<String, List<List<Object>>> loadColumnRows(Map<Long, String> nameById) {
        Map<String, List<List<Object>>> columns = new LinkedHashMap<>();
        for (ErdColumn c : columnRepository.findAllByOrderByTableIdAscSortOrderAsc()) {
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

    /** 문서 전체를 트랜잭션으로 교체한다 (시드 적재·JSON 불러오기·이력 복원용). */
    @Transactional
    public void replaceAll(SchemaDoc doc) {
        relationRepository.deleteAllInBatch();
        columnRepository.deleteAllInBatch();
        tableRepository.deleteAllInBatch();
        domainRepository.deleteAllInBatch();
        insertDomains(doc);
        Map<String, Long> tableIds = insertTables(doc);
        insertColumns(doc, tableIds);
        insertRelations(doc, tableIds);
    }

    private void insertDomains(SchemaDoc doc) {
        List<ErdDomain> entities = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, SchemaDoc.DomainDef> e : doc.domains().entrySet()) {
            entities.add(new ErdDomain(e.getKey(), e.getValue().name(), e.getValue().color(), order++));
        }
        domainRepository.saveAll(entities);
    }

    private Map<String, Long> insertTables(SchemaDoc doc) {
        List<ErdTable> entities = new ArrayList<>();
        for (int i = 0; i < doc.tables().size(); i++) {
            List<Object> row = doc.tables().get(i);
            entities.add(new ErdTable(Rows.str(row, 0), Rows.str(row, 1), Rows.str(row, 2),
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

    private void insertRelations(SchemaDoc doc, Map<String, Long> tableIds) {
        List<ErdRelation> entities = new ArrayList<>();
        for (int i = 0; i < doc.relations().size(); i++) {
            List<Object> row = doc.relations().get(i);
            Long childId = tableIds.get(Rows.str(row, 0));
            Long parentId = tableIds.get(Rows.str(row, 1));
            if (childId == null || parentId == null) {
                continue;
            }
            entities.add(new ErdRelation(childId, parentId, Rows.str(row, 2), i));
        }
        relationRepository.saveAll(entities);
    }
}
