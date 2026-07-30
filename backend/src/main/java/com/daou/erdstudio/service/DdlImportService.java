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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DDL 임포트: 파싱 결과를 현재 스키마와 결합해 새 SchemaDoc 을 만든다.
 * - merge: 기존 테이블 유지 + DDL 테이블 추가/갱신 (삭제 없음).
 *          신규 테이블은 이름 접두어로 기존 도메인에 배치하거나 새 도메인을 만든다.
 * - replace: DDL 에 있는 테이블만 남기고, 도메인 전체를 접두어 기반으로 재구성한다.
 *            (기존 위치·허브·관계는 최대한 보존)
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
    private static final List<String> PALETTE = List.of(
            "#4f8cff", "#37c98b", "#ff7a7a", "#ffb454", "#c17aff",
            "#00c2d1", "#e86ab0", "#8ab0d0", "#7fd3a8", "#ffd479");

    /** 접두어 → 한글 도메인명 사전 (대표 테이블 코멘트가 없을 때 사용). */
    private static final Map<String, String> PREFIX_KO = Map.ofEntries(
            Map.entry("user", "회원"), Map.entry("member", "회원"), Map.entry("customer", "고객"),
            Map.entry("order", "주문"), Map.entry("payment", "결제"), Map.entry("pay", "결제"),
            Map.entry("bill", "결제"), Map.entry("refund", "환불"), Map.entry("goods", "상품"),
            Map.entry("product", "상품"), Map.entry("item", "상품"), Map.entry("brand", "브랜드"),
            Map.entry("category", "카테고리"), Map.entry("cart", "장바구니"), Map.entry("coupon", "쿠폰"),
            Map.entry("point", "포인트"), Map.entry("deposit", "적립금"), Map.entry("voucher", "상품권"),
            Map.entry("event", "이벤트"), Map.entry("board", "게시판"), Map.entry("notice", "공지사항"),
            Map.entry("qna", "문의"), Map.entry("review", "리뷰"), Map.entry("admin", "관리자"),
            Map.entry("auth", "인증"), Map.entry("msg", "메시지"), Map.entry("message", "메시지"),
            Map.entry("stat", "통계"), Map.entry("log", "로그"), Map.entry("delivery", "배송"),
            Map.entry("course", "강좌"), Map.entry("lecture", "강의"), Map.entry("quiz", "퀴즈"),
            Map.entry("lms", "학습관리"), Map.entry("patient", "환자"), Map.entry("staff", "직원"),
            Map.entry("appointment", "예약"), Map.entry("treatment", "진료"), Map.entry("drug", "의약품"),
            Map.entry("stock", "재고"), Map.entry("inventory", "재고"), Map.entry("employee", "직원"),
            Map.entry("department", "부서"), Map.entry("reserve", "예약"), Map.entry("room", "객실"));

    private final DdlParser ddlParser;
    private final SchemaService schemaService;

    public DdlImportService(DdlParser ddlParser, SchemaService schemaService) {
        this.ddlParser = ddlParser;
        this.schemaService = schemaService;
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

        // 도메인 결정 — replace: 전체 재구성 / merge: 기존 유지 + 신규 테이블만 배치
        Map<String, SchemaDoc.DomainDef> domains;
        Map<String, String> domainOf = new HashMap<>();
        List<String> newDomains = new ArrayList<>();
        if (replace) {
            domains = inferDomains(parsed.tables(), domainOf, newDomains);
        } else {
            domains = new LinkedHashMap<>(current.domains());
            assignNewTableDomains(parsed, currentTables, current, domains, domainOf, newDomains);
        }

        List<List<Object>> tables = new ArrayList<>();
        Map<String, List<List<Object>>> columns = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int unchanged = 0;

        // 1) 기존 테이블: replace 면 DDL 에 있는 것만, merge 면 전부 유지
        for (Map.Entry<String, List<Object>> e : currentTables.entrySet()) {
            String name = e.getKey();
            ParsedTable p = parsedTables.get(name);
            if (p == null) {
                if (replace) {
                    removed.add(name);
                } else {
                    tables.add(e.getValue());
                    columns.put(name, current.columns().getOrDefault(name, List.of()));
                    unchanged++;
                }
                continue;
            }
            List<List<Object>> newCols = mergeColumns(p, current.columns().getOrDefault(name, List.of()));
            String desc = p.comment().isEmpty() ? Rows.str(e.getValue(), 2) : p.comment();
            String domainKey = replace ? domainOf.get(name) : Rows.str(e.getValue(), 1);
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

        // 2) 신규 테이블 (DDL 순서 유지)
        String fallback = domains.containsKey("stat") ? "stat" : domains.keySet().iterator().next();
        for (ParsedTable p : parsed.tables()) {
            if (currentTables.containsKey(p.name())) {
                continue;
            }
            String domainKey = domainOf.getOrDefault(p.name(), fallback);
            tables.add(Arrays.asList(p.name(), domainKey, p.comment(), false, null, null));
            columns.put(p.name(), toColumnRows(p, Map.of()));
            added.add(p.name());
        }

        // 3) 관계: 살아남은 기존 관계 + DDL FK 관계 (중복 제거)
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

        SchemaDoc doc = new SchemaDoc(domains, tables, relations, columns);
        return new ImportPlan(doc, new ImportSummary(added, updated, removed, unchanged, newRelations, newDomains));
    }

    /** 테이블명 접두어 (첫 '_' 앞부분, 없으면 전체 이름). */
    private String prefixOf(String name) {
        int idx = name.indexOf('_');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    /**
     * replace 모드: 접두어별로 묶어 도메인을 새로 만든다.
     * 같은 접두어 테이블이 2개 이상이면 도메인으로 승격, 1개짜리는 '기타'로 모은다.
     */
    private Map<String, SchemaDoc.DomainDef> inferDomains(List<ParsedTable> parsedTables,
                                                          Map<String, String> domainOf,
                                                          List<String> newDomains) {
        Map<String, List<ParsedTable>> groups = new LinkedHashMap<>();
        for (ParsedTable t : parsedTables) {
            groups.computeIfAbsent(prefixOf(t.name()), k -> new ArrayList<>()).add(t);
        }
        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        List<String> singles = new ArrayList<>();
        int colorIdx = 0;
        for (Map.Entry<String, List<ParsedTable>> e : groups.entrySet()) {
            if (e.getValue().size() < 2) {
                e.getValue().forEach(t -> singles.add(t.name()));
                continue;
            }
            String key = e.getKey();
            domains.put(key, new SchemaDoc.DomainDef(domainNameFor(key, e.getValue()),
                    PALETTE.get(colorIdx++ % PALETTE.size())));
            newDomains.add(key);
            e.getValue().forEach(t -> domainOf.put(t.name(), key));
        }
        if (!singles.isEmpty() || domains.isEmpty()) {
            domains.put(ETC_KEY, new SchemaDoc.DomainDef(ETC_NAME, ETC_COLOR));
            newDomains.add(ETC_KEY);
            singles.forEach(name -> domainOf.put(name, ETC_KEY));
        }
        return domains;
    }

    /**
     * 도메인 표시명(한글) 결정:
     * 1) 접두어와 이름이 같은 대표 테이블의 코멘트 → 2) 접두어 사전 → 3) 그룹 내
     * 가장 짧은 이름의 테이블 코멘트 → 4) 접두어 그대로.
     */
    private String domainNameFor(String prefix, List<ParsedTable> group) {
        for (ParsedTable t : group) {
            if (t.name().equals(prefix) && !t.comment().isBlank()) {
                return shortName(t.comment());
            }
        }
        String dict = PREFIX_KO.get(prefix);
        if (dict != null) {
            return dict;
        }
        ParsedTable shortest = group.stream()
                .min((a, b) -> Integer.compare(a.name().length(), b.name().length()))
                .orElse(null);
        if (shortest != null && !shortest.comment().isBlank()) {
            return shortName(shortest.comment());
        }
        return prefix;
    }

    /** 코멘트를 도메인명으로 축약 — 괄호/슬래시 이후는 버리고 12자 이내로 자른다. */
    private String shortName(String comment) {
        String s = comment.split("[(/,]")[0].trim();
        if (s.isEmpty()) {
            s = comment.trim();
        }
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    /**
     * merge 모드: 신규 테이블의 도메인을 정한다.
     * 1순위 — 같은 접두어를 가진 기존 테이블들의 다수결 도메인
     * 2순위 — 접두어와 동일한 키의 기존 도메인
     * 3순위 — 같은 접두어 신규 테이블이 2개 이상이면 새 도메인 생성
     * 그 외 — 기본 도메인(stat 또는 첫 도메인)
     */
    private void assignNewTableDomains(ParsedSchema parsed, Map<String, List<Object>> currentTables,
                                       SchemaDoc current, Map<String, SchemaDoc.DomainDef> domains,
                                       Map<String, String> domainOf, List<String> newDomains) {
        Map<String, Map<String, Integer>> prefixDomainCount = new HashMap<>();
        for (List<Object> row : current.tables()) {
            String prefix = prefixOf(Rows.str(row, 0));
            prefixDomainCount.computeIfAbsent(prefix, k -> new HashMap<>())
                    .merge(Rows.str(row, 1), 1, Integer::sum);
        }
        Map<String, List<ParsedTable>> newGroups = new LinkedHashMap<>();
        for (ParsedTable t : parsed.tables()) {
            if (!currentTables.containsKey(t.name())) {
                newGroups.computeIfAbsent(prefixOf(t.name()), k -> new ArrayList<>()).add(t);
            }
        }
        Set<String> usedColors = new HashSet<>();
        domains.values().forEach(d -> usedColors.add(d.color()));

        for (Map.Entry<String, List<ParsedTable>> e : newGroups.entrySet()) {
            String prefix = e.getKey();
            String key = majorityDomain(prefixDomainCount.get(prefix));
            if (key == null && domains.containsKey(prefix)) {
                key = prefix;
            }
            if (key == null && e.getValue().size() >= 2) {
                domains.put(prefix, new SchemaDoc.DomainDef(domainNameFor(prefix, e.getValue()),
                        pickColor(usedColors)));
                newDomains.add(prefix);
                key = prefix;
            }
            if (key != null) {
                for (ParsedTable t : e.getValue()) {
                    domainOf.put(t.name(), key);
                }
            } // null 이면 plan() 의 fallback(stat) 사용
        }
    }

    private String majorityDomain(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String pickColor(Set<String> usedColors) {
        for (String color : PALETTE) {
            if (usedColors.add(color)) {
                return color;
            }
        }
        return PALETTE.get(usedColors.size() % PALETTE.size());
    }

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
