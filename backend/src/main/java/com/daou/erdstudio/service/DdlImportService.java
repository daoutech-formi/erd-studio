package com.daou.erdstudio.service;

import com.daou.erdstudio.service.DdlParser.ParsedColumn;
import com.daou.erdstudio.service.DdlParser.ParsedRelation;
import com.daou.erdstudio.service.DdlParser.ParsedSchema;
import com.daou.erdstudio.service.DdlParser.ParsedTable;
import com.daou.erdstudio.web.dto.SchemaDoc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DDL 임포트: 파싱 결과를 현재 스키마와 결합해 새 SchemaDoc 을 만든다.
 * - merge: 기존 테이블 유지 + DDL 테이블 추가/갱신 (삭제 없음)
 * - replace: DDL 에 있는 테이블만 남김 (위치·허브 보존, 도메인 전체 재생성)
 *
 * 도메인(자동 분류):
 *   1) 키워드 사전 + 가중치({@link DomainClassifier}) — 코멘트/테이블명 전체의 시그널 단어로 판정
 *   2) 사전 미매칭이면 코멘트 대표 단어 → 테이블명 토큰 순으로 폴백
 *   3) 같은 토픽 2개 이상 → 도메인 승격. 단독/초과 도메인은 FK 이웃에 흡수 후 '기타'로 병합.
 *      도메인 수는 MAX_DOMAINS(12)개로 제한한다.
 *
 * 관계: DDL 의 FOREIGN KEY 우선, 없으면 컬럼명 규칙(user_no → PK 가 user_no 인 테이블)으로 추론.
 */
@Service
public class DdlImportService {

    /** 임포트 미리보기/결과 요약. */
    public record ImportSummary(List<String> added, List<String> updated, List<String> removed,
                                int unchanged, int newRelations, List<String> newDomains) {
    }

    public record ImportPlan(SchemaDoc doc, ImportSummary summary) {
    }

    private static final String ETC_KEY = "etc";
    private static final String ETC_NAME = "기타";
    private static final String ETC_COLOR = "#9aa0aa";
    private static final int MIN_GROUP_SIZE = 2;
    private static final int MAX_DOMAINS = 12;
    private static final int DOMINANT_MIN_TABLES = 5;
    private static final List<String> PALETTE = List.of(
            "#4f8cff", "#37c98b", "#ff7a7a", "#ffb454", "#c17aff",
            "#00c2d1", "#e86ab0", "#8ab0d0", "#7fd3a8", "#ffd479", "#6bd0e0");

    /** 토픽으로 쓰기에 무의미한 일반어 — 코멘트에서 건너뛰거나 접미사로 잘라낸다. */
    private static final Set<String> GENERIC_WORDS = Set.of(
            "정보", "내역", "이력", "관리", "목록", "현황", "상세", "기록",
            "테이블", "데이터", "기본", "공통", "번호", "설정");

    private final DdlParser ddlParser;
    private final SchemaService schemaService;
    private final DomainClassifier domainClassifier;
    private final LlmDomainClassifier llmClassifier;

    public DdlImportService(DdlParser ddlParser, SchemaService schemaService,
                            DomainClassifier domainClassifier, LlmDomainClassifier llmClassifier) {
        this.ddlParser = ddlParser;
        this.schemaService = schemaService;
        this.domainClassifier = domainClassifier;
        this.llmClassifier = llmClassifier;
    }

    /** DDL 을 파싱해 적용할 새 문서와 변경 요약을 만든다. 적용은 호출자가 op 로 수행한다. */
    @Transactional(readOnly = true)
    public ImportPlan plan(String ddl, String mode) {
        boolean replace = "replace".equalsIgnoreCase(mode);
        ParsedSchema parsed = ddlParser.parse(ddl);
        SchemaDoc current = schemaService.loadDoc();

        Map<String, List<Object>> currentTables = new LinkedHashMap<>();
        for (List<Object> row : current.tables()) {
            currentTables.put(Rows.str(row, 0), row);
        }
        Map<String, ParsedTable> parsedTables = new LinkedHashMap<>();
        for (ParsedTable t : parsed.tables()) {
            parsedTables.put(t.name(), t);
        }

        List<List<Object>> tables = new ArrayList<>();
        Map<String, List<List<Object>>> columns = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int unchanged = 0;

        // 1) 기존 테이블: replace 면 DDL 에 있는 것만, merge 면 전부 유지.
        //    도메인은 replace 일 때 나중에 재배치하므로 우선 null 로 둔다.
        for (Map.Entry<String, List<Object>> e : currentTables.entrySet()) {
            String name = e.getKey();
            ParsedTable p = parsedTables.get(name);
            if (p == null) {
                if (replace) {
                    removed.add(name);
                } else {
                    tables.add(e.getValue());
                    columns.put(name, mutableRows(current.columns().getOrDefault(name, List.of())));
                    unchanged++;
                }
                continue;
            }
            List<List<Object>> newCols = mergeColumns(p, current.columns().getOrDefault(name, List.of()));
            String desc = p.comment().isEmpty() ? Rows.str(e.getValue(), 2) : p.comment();
            String domainKey = replace ? null : Rows.str(e.getValue(), 1);
            List<Object> row = Arrays.asList(name, domainKey, desc,
                    Rows.bool(e.getValue(), 3), e.getValue().get(4), e.getValue().get(5));
            tables.add(row);
            columns.put(name, newCols);
            if (differs(newCols, current.columns().getOrDefault(name, List.of()))
                    || !desc.equals(Rows.str(e.getValue(), 2))) {
                updated.add(name);
            } else {
                unchanged++;
            }
        }

        // 2) 신규 테이블 (DDL 순서 유지) — 도메인은 아래에서 일괄 배치
        for (ParsedTable p : parsed.tables()) {
            if (currentTables.containsKey(p.name())) {
                continue;
            }
            tables.add(Arrays.asList(p.name(), null, p.comment(), false, null, null));
            columns.put(p.name(), toColumnRows(p, Map.of()));
            added.add(p.name());
        }

        // 3) 관계: 살아남은 기존 관계 + DDL FK 관계 + 컬럼명 기반 추론 (중복 제거)
        Set<String> tableNames = new HashSet<>();
        tables.forEach(row -> tableNames.add(Rows.str(row, 0)));
        List<List<Object>> relations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Object> row : current.relations()) {
            if (tableNames.contains(Rows.str(row, 0)) && tableNames.contains(Rows.str(row, 1))
                    && seen.add(Rows.str(row, 0) + "→" + Rows.str(row, 1))) {
                relations.add(row);
            }
        }
        int newRelations = 0;
        for (ParsedRelation r : parsed.relations()) {
            if (tableNames.contains(r.child()) && tableNames.contains(r.parent())
                    && seen.add(r.child() + "→" + r.parent())) {
                String label = r.childCol().equals(r.parentCol()) ? "" : r.childCol();
                relations.add(label.isEmpty() ? List.of(r.child(), r.parent())
                        : List.of(r.child(), r.parent(), label));
                newRelations++;
            }
        }
        newRelations += inferRelationsByName(tables, columns, relations, seen);

        // 4) 도메인 — 코멘트 토픽 기반 자동 생성/배치 (관계 정보를 활용하므로 관계 이후에 수행)
        Map<String, SchemaDoc.DomainDef> domains;
        List<String> newDomains = new ArrayList<>();
        if (replace) {
            domains = inferDomains(tables, relations, newDomains);
        } else {
            domains = new LinkedHashMap<>(current.domains());
            assignNewTables(tables, relations, domains, newDomains);
        }

        SchemaDoc doc = new SchemaDoc(domains, tables, relations, columns);
        return new ImportPlan(doc, new ImportSummary(added, updated, removed, unchanged, newRelations, newDomains));
    }

    // ==================== 도메인 자동 분류 ====================

    /** 토픽 판정 결과 — 테이블별 토픽 + LLM 이 확정한 도메인명(승격/병합 보호용). */
    private record Topics(Map<String, String> byTable, Set<String> llmDomains) {
    }

    /** replace 모드: 전체 테이블을 토픽으로 그룹핑해 도메인을 새로 만든다. */
    private Map<String, SchemaDoc.DomainDef> inferDomains(List<List<Object>> tables,
                                                          List<List<Object>> relations,
                                                          List<String> newDomains) {
        Topics topics = extractTopics(tables);
        Map<String, String> topicByTable = topics.byTable();

        // 토픽 → 테이블 그룹 (등장 순서 유지)
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : topicByTable.entrySet()) {
            groups.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        // 크기순으로 도메인 승격 (MAX_DOMAINS-1 개까지, 나머지는 이웃/기타로).
        // 동점이면 사전 정의 순서가 앞선 도메인을 우선한다.
        // 사전/LLM 이 확정한 도메인은 테이블이 1개여도 승격 대상에 포함한다.
        Set<String> confident = new LinkedHashSet<>(domainClassifier.knownDomains());
        confident.addAll(topics.llmDomains());
        List<Map.Entry<String, List<String>>> promoted = groups.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_GROUP_SIZE || confident.contains(e.getKey()))
                .sorted((a, b) -> {
                    int bySize = Integer.compare(b.getValue().size(), a.getValue().size());
                    return bySize != 0 ? bySize
                            : Integer.compare(domainClassifier.priorityOf(a.getKey()),
                                              domainClassifier.priorityOf(b.getKey()));
                })
                .limit(MAX_DOMAINS - 1L)
                .toList();

        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        Map<String, String> keyByTable = new HashMap<>();
        int colorIdx = 0;
        for (Map.Entry<String, List<String>> e : promoted) {
            String key = uniqueKey(domains, e.getKey(), colorIdx);
            domains.put(key, new SchemaDoc.DomainDef(e.getKey(), PALETTE.get(colorIdx++ % PALETTE.size())));
            newDomains.add(e.getKey());
            e.getValue().forEach(t -> keyByTable.put(t, key));
        }

        applyKeys(tables, keyByTable);
        assignByNeighbors(tables, relations);

        // 끝까지 미배치 → 기타
        boolean needEtc = tables.stream().anyMatch(r -> r.get(1) == null);
        if (needEtc || domains.isEmpty()) {
            domains.put(ETC_KEY, new SchemaDoc.DomainDef(ETC_NAME, ETC_COLOR));
            newDomains.add(ETC_NAME);
            tables.forEach(r -> {
                if (r.get(1) == null) {
                    r.set(1, ETC_KEY);
                }
            });
        }
        return domains;
    }

    /** merge 모드: 도메인이 비어 있는(신규) 테이블만 배치한다. 기존 도메인·배치는 그대로. */
    private void assignNewTables(List<List<Object>> tables, List<List<Object>> relations,
                                 Map<String, SchemaDoc.DomainDef> domains, List<String> newDomains) {
        Map<String, String> topicByTable = extractTopics(tables).byTable();

        Map<String, List<List<Object>>> pendingByTopic = new LinkedHashMap<>();
        for (List<Object> row : tables) {
            if (row.get(1) == null) {
                pendingByTopic.computeIfAbsent(topicByTable.get(Rows.str(row, 0)), k -> new ArrayList<>()).add(row);
            }
        }
        int colorIdx = domains.size();
        for (Map.Entry<String, List<List<Object>>> e : pendingByTopic.entrySet()) {
            String topic = e.getKey();
            String key = findDomainByName(domains, topic);
            if (key == null && e.getValue().size() >= MIN_GROUP_SIZE && domains.size() < MAX_DOMAINS) {
                key = uniqueKey(domains, topic, colorIdx);
                domains.put(key, new SchemaDoc.DomainDef(topic, PALETTE.get(colorIdx++ % PALETTE.size())));
                newDomains.add(topic);
            }
            if (key != null) {
                for (List<Object> row : e.getValue()) {
                    row.set(1, key);
                }
            }
        }
        assignByNeighbors(tables, relations);

        // 남은 테이블 → 기존 stat/기타 가 있으면 거기로, 없으면 기타 생성
        String fallback = domains.containsKey("stat") ? "stat"
                : domains.containsKey(ETC_KEY) ? ETC_KEY : null;
        boolean pending = tables.stream().anyMatch(r -> r.get(1) == null);
        if (pending && fallback == null) {
            domains.put(ETC_KEY, new SchemaDoc.DomainDef(ETC_NAME, ETC_COLOR));
            newDomains.add(ETC_NAME);
            fallback = ETC_KEY;
        }
        for (List<Object> row : tables) {
            if (row.get(1) == null) {
                row.set(1, fallback);
            }
        }
    }

    /**
     * 테이블별 토픽 추출.
     * 0) API 키가 있으면 LLM 일괄 분류 (실패 시 아래로 폴백)
     * 1) 키워드 사전 + 가중치 판정 → 2) 코멘트 대표 단어 → 3) 테이블명 토큰.
     * 포함관계 토픽은 병합한다.
     */
    private Topics extractTopics(List<List<Object>> tables) {
        List<String> names = new ArrayList<>();
        tables.forEach(r -> names.add(Rows.str(r, 0)));
        String dominant = detectDominantPrefix(names);

        Map<String, String> llmTopics = Map.of();
        if (llmClassifier.isEnabled()) {
            Map<String, String> commentByTable = new LinkedHashMap<>();
            tables.forEach(r -> commentByTable.put(Rows.str(r, 0), Rows.str(r, 2)));
            llmTopics = llmClassifier.classifyAll(commentByTable);
        }

        Map<String, String> topicByTable = new LinkedHashMap<>();
        for (List<Object> row : tables) {
            String name = Rows.str(row, 0);
            String comment = Rows.str(row, 2);
            String topic = llmTopics.get(name);
            if (topic == null) {
                topic = domainClassifier.classify(name, comment);
            }
            if (topic == null) {
                topic = commentTopic(comment);
            }
            topicByTable.put(name, topic != null ? topic : nameTopic(name, dominant));
        }

        // 포함관계 병합: '비즈회원'·'진료과' 처럼 다른 토픽을 포함하면 짧은 쪽으로 흡수.
        // 사전/LLM 이 정한 도메인명은 이미 체계이므로 병합하지 않는다('메일' ⊂ '메일함' 오흡수 방지).
        Set<String> known = new LinkedHashSet<>(domainClassifier.knownDomains());
        known.addAll(llmTopics.values());
        Set<String> topics = new LinkedHashSet<>(topicByTable.values());
        Map<String, String> remap = new HashMap<>();
        for (String t : topics) {
            if (known.contains(t)) {
                continue;
            }
            String base = null;
            for (String s : topics) {
                if (!s.equals(t) && s.length() >= 2 && t.contains(s)
                        && (base == null || s.length() < base.length())) {
                    base = s;
                }
            }
            if (base != null) {
                remap.put(t, base);
            }
        }
        if (!remap.isEmpty()) {
            topicByTable.replaceAll((name, t) -> remap.getOrDefault(t, t));
        }
        return new Topics(topicByTable, new LinkedHashSet<>(llmTopics.values()));
    }

    /** 코멘트에서 대표 단어 추출 — 한글 우선, 일반어(정보/내역/관리…)는 건너뛰거나 접미사 제거. */
    private String commentTopic(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String ascii = null;
        for (String raw : comment.split("[\\s(),/·\\-\\[\\]:]+")) {
            String tok = stripGenericSuffix(raw.trim());
            if (tok.length() < 2 || GENERIC_WORDS.contains(tok)) {
                continue;
            }
            if (hasKorean(tok)) {
                return tok;
            }
            if (ascii == null && tok.matches("[A-Za-z0-9]{2,}")) {
                ascii = tok.toLowerCase(Locale.ROOT);
            }
        }
        return ascii;
    }

    /** '구매적립정보' → '구매적립' 처럼 붙어 있는 일반어 접미사를 잘라낸다. */
    private String stripGenericSuffix(String token) {
        String t = token;
        boolean changed = true;
        while (changed && t.length() > 2) {
            changed = false;
            for (String g : GENERIC_WORDS) {
                if (t.length() > g.length() + 1 && t.endsWith(g)) {
                    t = t.substring(0, t.length() - g.length());
                    changed = true;
                }
            }
        }
        return t;
    }

    private boolean hasKorean(String s) {
        return s.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
    }

    /** 테이블명 토픽 — 서비스 공통 접두어(donut_ 등)는 건너뛰고 다음 토큰을 쓴다. */
    private String nameTopic(String name, String dominant) {
        String n = name.toLowerCase(Locale.ROOT);
        String first = n.contains("_") ? n.substring(0, n.indexOf('_')) : n;
        if (dominant != null && first.equals(dominant) && n.length() > dominant.length() + 1) {
            String rest = n.substring(dominant.length() + 1);
            return rest.contains("_") ? rest.substring(0, rest.indexOf('_')) : rest;
        }
        return first;
    }

    /** 서비스 공통 접두어 감지 — 과반 + DOMINANT_MIN_TABLES 개 이상. */
    private String detectDominantPrefix(Collection<String> names) {
        Map<String, Integer> counts = new HashMap<>();
        for (String name : names) {
            String n = name.toLowerCase(Locale.ROOT);
            String first = n.contains("_") ? n.substring(0, n.indexOf('_')) : n;
            counts.merge(first, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= DOMINANT_MIN_TABLES && e.getValue() * 2 > names.size()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 도메인이 없는 테이블을 FK 이웃(부모/자식)의 다수결 도메인으로 흡수한다 (2회 반복). */
    private void assignByNeighbors(List<List<Object>> tables, List<List<Object>> relations) {
        Map<String, List<Object>> rowByName = new HashMap<>();
        tables.forEach(r -> rowByName.put(Rows.str(r, 0), r));
        for (int pass = 0; pass < 2; pass++) {
            for (List<Object> row : tables) {
                if (row.get(1) != null) {
                    continue;
                }
                String name = Rows.str(row, 0);
                Map<String, Integer> counts = new HashMap<>();
                for (List<Object> rel : relations) {
                    String other = null;
                    if (name.equals(Rows.str(rel, 0))) {
                        other = Rows.str(rel, 1);
                    } else if (name.equals(Rows.str(rel, 1))) {
                        other = Rows.str(rel, 0);
                    }
                    if (other == null) {
                        continue;
                    }
                    List<Object> otherRow = rowByName.get(other);
                    if (otherRow != null && otherRow.get(1) != null) {
                        counts.merge(String.valueOf(otherRow.get(1)), 1, Integer::sum);
                    }
                }
                counts.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .ifPresent(best -> row.set(1, best.getKey()));
            }
        }
    }

    private void applyKeys(List<List<Object>> tables, Map<String, String> keyByTable) {
        for (List<Object> row : tables) {
            String key = keyByTable.get(Rows.str(row, 0));
            if (key != null) {
                row.set(1, key);
            }
        }
    }

    /** 도메인 키 — 토픽이 영문/숫자면 그대로, 아니면 d1, d2… (중복 회피). */
    private String uniqueKey(Map<String, SchemaDoc.DomainDef> domains, String topic, int seq) {
        String base = topic.matches("[a-z0-9]+") ? topic : "d" + (seq + 1);
        String key = base;
        int n = 2;
        while (domains.containsKey(key) || ETC_KEY.equals(key)) {
            key = base + n++;
        }
        return key;
    }

    /** 토픽과 이름이 겹치는 기존 도메인 찾기 (회원 ↔ 회원/인증 매칭). */
    private String findDomainByName(Map<String, SchemaDoc.DomainDef> domains, String topic) {
        if (topic == null || topic.length() < 2) {
            return null;
        }
        for (Map.Entry<String, SchemaDoc.DomainDef> e : domains.entrySet()) {
            String name = e.getValue().name();
            if (name.contains(topic) || (name.length() >= 2 && topic.contains(name))) {
                return e.getKey();
            }
        }
        return null;
    }

    // ==================== 컬럼명 기반 관계 추론 ====================

    /**
     * FK 제약이 없는 스키마를 위해 컬럼명으로 관계를 추론한다.
     * 규칙: 컬럼명이 다른 테이블의 단일 PK 명과 같으면(user_no 등) 그 테이블을 부모로 연결.
     *       b2c_goods_no 처럼 접미사로 끝나면 라벨을 붙여 연결한다.
     */
    private int inferRelationsByName(List<List<Object>> tables, Map<String, List<List<Object>>> columns,
                                     List<List<Object>> relations, Set<String> seen) {
        Map<String, List<String>> pkOwners = new LinkedHashMap<>();
        Map<String, String> pkOf = new HashMap<>();
        for (List<Object> t : tables) {
            String name = Rows.str(t, 0);
            List<String> pks = new ArrayList<>();
            for (List<Object> c : columns.getOrDefault(name, List.of())) {
                if (Rows.str(c, 3).contains("PK")) {
                    pks.add(Rows.str(c, 0));
                }
            }
            if (pks.size() == 1) {
                pkOwners.computeIfAbsent(pks.get(0), k -> new ArrayList<>()).add(name);
                pkOf.put(name, pks.get(0));
            }
        }

        int added = 0;
        for (List<Object> t : tables) {
            String child = Rows.str(t, 0);
            List<List<Object>> childCols = columns.getOrDefault(child, List.of());
            for (List<Object> c : childCols) {
                String colName = Rows.str(c, 0);
                if (!colName.endsWith("_no") && !colName.endsWith("_id")) {
                    continue;
                }
                String parent;
                String label = "";
                List<String> owners = pkOwners.get(colName);
                if (owners != null) {
                    boolean ownPk = colName.equals(pkOf.get(child));
                    parent = pickParent(child, colName, owners, ownPk);
                } else {
                    // 접미사 매칭: b2c_goods_no → goods_no (가장 긴 PK 명 우선)
                    String bestPk = null;
                    for (String pk : pkOwners.keySet()) {
                        if (colName.endsWith("_" + pk) && (bestPk == null || pk.length() > bestPk.length())) {
                            bestPk = pk;
                        }
                    }
                    if (bestPk == null) {
                        continue;
                    }
                    parent = pickParent(child, bestPk, pkOwners.get(bestPk), false);
                    label = colName;
                }
                if (parent == null || parent.equals(child) || !seen.add(child + "→" + parent)) {
                    continue;
                }
                relations.add(label.isEmpty() ? List.of(child, parent) : List.of(child, parent, label));
                markFk(childCols, colName);
                added++;
            }
        }
        return added;
    }

    /**
     * 부모 후보 선택 — 점수: 자식과의 공통 선행 토큰 수(최우선) + 정확 일치/정규형 가점.
     * 정규형 = "서비스접두어_어간" 형태(donut_user 등).
     * 자기 PK 컬럼으로 연결할 때(event_goods.content_no 등)는 정확/정규형 부모만 허용한다.
     */
    private String pickParent(String child, String pkCol, List<String> owners, boolean ownPk) {
        String stem = pkCol.toLowerCase(Locale.ROOT).replaceAll("_(no|id)$", "");
        Pattern canonicalForm = Pattern.compile("[a-z0-9]+_" + Pattern.quote(stem));
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String owner : owners) {
            if (owner.equals(child)) {
                continue;
            }
            String o = owner.toLowerCase(Locale.ROOT);
            boolean exact = o.equals(stem);
            boolean canonical = canonicalForm.matcher(o).matches();
            int common = commonTokenCount(o, child.toLowerCase(Locale.ROOT));
            boolean firstTokenIsStem = common >= 1 && o.split("_")[0].equals(stem);
            if (!(exact || canonical || common >= 2 || firstTokenIsStem)) {
                continue;
            }
            if (ownPk && !exact && !canonical) {
                continue;
            }
            int score = common * 100 + (exact ? 50 : 0) + (canonical ? 40 : 0) - owner.length();
            if (score > bestScore) {
                bestScore = score;
                best = owner;
            }
        }
        return best;
    }

    /** 두 이름의 선행 공통 토큰 수 (donut_point_use vs donut_point_history → 2). */
    private int commonTokenCount(String a, String b) {
        String[] ta = a.split("_");
        String[] tb = b.split("_");
        int n = 0;
        while (n < ta.length && n < tb.length && ta[n].equals(tb[n])) {
            n++;
        }
        return n;
    }

    /** 추론된 참조 컬럼에 FK 플래그를 붙인다 (기존 플래그 뒤에 추가). */
    private void markFk(List<List<Object>> rows, String colName) {
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            if (!colName.equals(Rows.str(row, 0))) {
                continue;
            }
            String flag = Rows.str(row, 3);
            if (flag.contains("FK")) {
                return;
            }
            String newFlag = flag.isEmpty() ? "FK" : flag + "/FK";
            rows.set(i, List.of(Rows.str(row, 0), Rows.str(row, 1), Rows.str(row, 2), newFlag));
            return;
        }
    }

    // ==================== 컬럼 병합 ====================

    /** DDL 컬럼을 기준으로 하되, 같은 이름 컬럼의 기존 플래그(FK 등)는 합쳐서 보존한다. */
    private List<List<Object>> mergeColumns(ParsedTable parsed, List<List<Object>> currentCols) {
        Map<String, String> currentFlags = new HashMap<>();
        for (List<Object> row : currentCols) {
            currentFlags.put(Rows.str(row, 0), Rows.str(row, 3));
        }
        return toColumnRows(parsed, currentFlags);
    }

    private List<List<Object>> toColumnRows(ParsedTable parsed, Map<String, String> currentFlags) {
        List<List<Object>> rows = new ArrayList<>();
        for (ParsedColumn c : parsed.columns()) {
            Set<String> flags = new LinkedHashSet<>();
            String existing = currentFlags.getOrDefault(c.name(), "");
            if (!existing.isEmpty()) {
                flags.addAll(Arrays.asList(existing.split("/")));
            }
            flags.addAll(c.flags());
            String flag = String.join("/", orderFlags(flags));
            rows.add(flag.isEmpty() ? List.of(c.name(), c.colType(), c.comment())
                    : List.of(c.name(), c.colType(), c.comment(), flag));
        }
        return rows;
    }

    private List<List<Object>> mutableRows(List<List<Object>> rows) {
        return new ArrayList<>(rows);
    }

    /** 플래그 표기 순서를 PK, UK, FK 로 고정한다. */
    private List<String> orderFlags(Set<String> flags) {
        List<String> out = new ArrayList<>();
        for (String f : List.of("PK", "UK", "FK")) {
            if (flags.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private boolean differs(List<List<Object>> a, List<List<Object>> b) {
        if (a.size() != b.size()) {
            return true;
        }
        for (int i = 0; i < a.size(); i++) {
            List<Object> x = a.get(i);
            List<Object> y = b.get(i);
            for (int j = 0; j < 4; j++) {
                if (!Objects.equals(j < x.size() ? x.get(j) : "", j < y.size() ? y.get(j) : "")) {
                    return true;
                }
            }
        }
        return false;
    }
}
