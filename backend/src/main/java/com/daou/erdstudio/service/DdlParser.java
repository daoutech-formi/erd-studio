package com.daou.erdstudio.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL/MariaDB/PostgreSQL DDL 텍스트를 파싱해 테이블/컬럼/관계 정보를 추출한다.
 * 지원: CREATE TABLE(백틱/쌍따옴표/스키마 접두어), 컬럼 타입·unsigned·COMMENT,
 *       PostgreSQL 다단어 타입(character varying 등)·serial·인라인 PRIMARY KEY/REFERENCES,
 *       PRIMARY KEY, UNIQUE KEY(단일 컬럼), inline/ALTER TABLE FOREIGN KEY,
 *       pg_dump 스타일 ALTER TABLE ONLY … PRIMARY KEY/UNIQUE, CREATE UNIQUE INDEX,
 *       COMMENT ON TABLE/COLUMN.
 */
@Component
public class DdlParser {

    /** 파싱된 테이블 하나. columns 행: [name, colType, comment, flags(Set)] */
    public record ParsedColumn(String name, String colType, String comment, Set<String> flags) {
    }

    public record ParsedTable(String name, String comment, List<ParsedColumn> columns) {
    }

    /** 관계: child.col → parent (label은 컬럼명이 부모 PK명과 다를 때만 채운다) */
    public record ParsedRelation(String child, String parent, String childCol, String parentCol) {
    }

    public record ParsedSchema(List<ParsedTable> tables, List<ParsedRelation> relations) {
    }

    /** `sch`."name" 같은 스키마 접두어 (캡처하지 않음). */
    private static final String SCHEMA = "(?:[`\"]?[A-Za-z0-9_]+[`\"]?\\.)?";
    /** 인용부호를 허용하는 식별자 — 이름만 캡처한다. */
    private static final String IDENT = "[`\"]?([A-Za-z0-9_]+)[`\"]?";
    /** '' 이스케이프를 허용하는 문자열 리터럴 — 내용만 캡처한다. */
    private static final String STRING = "'((?:[^']|'')*)'";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + SCHEMA + IDENT + "\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_COMMENT = Pattern.compile(
            "COMMENT\\s*=?\\s*" + STRING, Pattern.CASE_INSENSITIVE);

    /** 타입: 기본 단어 + varying/precision + (n[,m]) + with(out) time zone 까지 한 덩어리로 잡는다. */
    private static final Pattern COLUMN_DEF = Pattern.compile(
            "^[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+([A-Za-z]+(?:\\s+(?:VARYING|PRECISION))?(?:\\s*\\([^)]*\\))?"
                    + "(?:\\s+WITH(?:OUT)?\\s+TIME\\s+ZONE)?)((?:\\s+UNSIGNED)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COLUMN_COMMENT = Pattern.compile(
            "COMMENT\\s+" + STRING, Pattern.CASE_INSENSITIVE);

    private static final Pattern INLINE_PK = Pattern.compile(
            "\\bPRIMARY\\s+KEY\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern INLINE_UNIQUE = Pattern.compile(
            "\\bUNIQUE\\b", Pattern.CASE_INSENSITIVE);

    /** 컬럼 정의 뒤에 붙는 인라인 참조 (PostgreSQL): user_id integer REFERENCES users(id) */
    private static final Pattern INLINE_COL_REF = Pattern.compile(
            "\\bREFERENCES\\s+" + SCHEMA + IDENT + "\\s*(?:\\(([^)]*)\\))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "^PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "^UNIQUE\\s+(?:KEY|INDEX)?\\s*[`\"]?[A-Za-z0-9_]*[`\"]?\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INLINE_FK = Pattern.compile(
            "FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+" + SCHEMA + IDENT + "\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final String ALTER_HEAD =
            "ALTER\\s+TABLE\\s+(?:ONLY\\s+)?" + SCHEMA + IDENT
                    + "\\s+ADD\\s+(?:CONSTRAINT\\s+[`\"]?[A-Za-z0-9_]+[`\"]?\\s+)?";

    private static final Pattern ALTER_FK = Pattern.compile(
            ALTER_HEAD + "FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+" + SCHEMA + IDENT + "\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    /** pg_dump 가 CREATE TABLE 밖으로 빼는 PK/UNIQUE 제약. */
    private static final Pattern ALTER_PK = Pattern.compile(
            ALTER_HEAD + "PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_UK = Pattern.compile(
            ALTER_HEAD + "UNIQUE\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNIQUE_INDEX = Pattern.compile(
            "CREATE\\s+UNIQUE\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "[`\"]?[A-Za-z0-9_]*[`\"]?\\s+ON\\s+(?:ONLY\\s+)?" + SCHEMA + IDENT
                    + "\\s*(?:USING\\s+[A-Za-z]+\\s*)?\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT_ON_TABLE = Pattern.compile(
            "COMMENT\\s+ON\\s+TABLE\\s+" + SCHEMA + IDENT + "\\s+IS\\s+" + STRING,
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT_ON_COLUMN = Pattern.compile(
            "COMMENT\\s+ON\\s+COLUMN\\s+(?:[`\"]?[A-Za-z0-9_]+[`\"]?\\.)*"
                    + IDENT + "\\." + IDENT + "\\s+IS\\s+" + STRING,
            Pattern.CASE_INSENSITIVE);

    /** 제약절 시작 키워드 — 컬럼 정의와 구분한다. */
    private static final Set<String> CONSTRAINT_STARTERS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL",
            "CHECK", "EXCLUDE", "LIKE");

    private static final Set<String> INT_TYPES = Set.of("int", "tinyint", "smallint", "mediumint", "bigint");

    public ParsedSchema parse(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            throw new IllegalArgumentException("DDL 내용이 비어 있습니다.");
        }
        String text = stripComments(ddl);
        List<ParsedTable> tables = new ArrayList<>();
        List<ParsedRelation> relations = new ArrayList<>();

        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            String tableName = m.group(1);
            int bodyStart = m.end(); // '(' 다음 위치
            int bodyEnd = findMatchingParen(text, bodyStart - 1);
            if (bodyEnd < 0) {
                throw new IllegalArgumentException("괄호가 맞지 않습니다: CREATE TABLE " + tableName);
            }
            String body = text.substring(bodyStart, bodyEnd);
            int stmtEnd = text.indexOf(';', bodyEnd);
            String tail = text.substring(bodyEnd + 1, stmtEnd < 0 ? text.length() : stmtEnd);
            tables.add(parseTable(tableName, body, tail, relations));
        }
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "CREATE TABLE 문을 찾지 못했습니다. MySQL/MariaDB/PostgreSQL DDL인지 확인하세요.");
        }

        Matcher af = ALTER_FK.matcher(text);
        while (af.find()) {
            relations.add(new ParsedRelation(af.group(1), af.group(3),
                    firstIdentifier(af.group(2)), firstIdentifier(af.group(4))));
        }
        applyAlterConstraints(text, tables);
        tables = applyCommentOn(text, tables);
        applyFkFlags(tables, relations);
        return new ParsedSchema(tables, dedupe(relations));
    }

    private ParsedTable parseTable(String name, String body, String tail, List<ParsedRelation> relations) {
        Map<String, ParsedColumn> columns = new LinkedHashMap<>();
        for (String part : splitTopLevel(body)) {
            String line = part.trim();
            if (line.isEmpty()) {
                continue;
            }
            String firstWord = line.split("[\\s(`\"]+", 2)[0].toUpperCase(Locale.ROOT);
            if (CONSTRAINT_STARTERS.contains(firstWord)) {
                parseConstraint(name, line, columns, relations);
            } else {
                ParsedColumn col = parseColumn(name, line, relations);
                if (col != null) {
                    columns.put(col.name(), col);
                }
            }
        }
        Matcher tc = TABLE_COMMENT.matcher(tail);
        String comment = tc.find() ? unescape(tc.group(1)) : "";
        return new ParsedTable(name, comment, new ArrayList<>(columns.values()));
    }

    private ParsedColumn parseColumn(String tableName, String line, List<ParsedRelation> relations) {
        Matcher m = COLUMN_DEF.matcher(line);
        if (!m.find()) {
            return null;
        }
        String colName = m.group(1);
        String type = normalizeType(m.group(2), !m.group(3).isBlank());
        String rest = line.substring(m.end());
        Matcher cm = COLUMN_COMMENT.matcher(rest);
        String comment = cm.find() ? unescape(cm.group(1)) : "";

        Set<String> flags = new LinkedHashSet<>();
        if (INLINE_PK.matcher(rest).find()) {
            flags.add("PK");
        } else if (INLINE_UNIQUE.matcher(rest).find()) {
            flags.add("UK");
        }
        Matcher ref = INLINE_COL_REF.matcher(rest);
        if (ref.find()) {
            String parentCol = ref.group(2) == null ? colName : firstIdentifier(ref.group(2));
            relations.add(new ParsedRelation(tableName, ref.group(1), colName, parentCol));
        }
        return new ParsedColumn(colName, type, comment, flags);
    }

    private void parseConstraint(String tableName, String line,
                                 Map<String, ParsedColumn> columns, List<ParsedRelation> relations) {
        // PostgreSQL 은 CONSTRAINT 이름을 붙인 인라인 제약이 흔하다 — 이름을 떼고 판정한다.
        String stripped = line.replaceFirst("(?i)^CONSTRAINT\\s+[`\"]?[A-Za-z0-9_]+[`\"]?\\s+", "");
        Matcher pk = PRIMARY_KEY.matcher(stripped);
        if (pk.find()) {
            for (String col : identifiers(pk.group(1))) {
                flag(columns, col, "PK");
            }
            return;
        }
        Matcher uk = UNIQUE_KEY.matcher(stripped);
        if (uk.find()) {
            List<String> cols = identifiers(uk.group(1));
            if (cols.size() == 1) { // 복합 유니크는 단일 컬럼 UK 로 오해될 수 있어 표기 생략
                flag(columns, cols.get(0), "UK");
            }
            return;
        }
        Matcher fk = INLINE_FK.matcher(stripped);
        if (fk.find()) {
            relations.add(new ParsedRelation(tableName, fk.group(2),
                    firstIdentifier(fk.group(1)), firstIdentifier(fk.group(3))));
        }
        // KEY/INDEX 등 나머지 제약은 무시
    }

    /** pg_dump 스타일 ALTER TABLE … PRIMARY KEY/UNIQUE, CREATE UNIQUE INDEX 를 플래그로 반영한다. */
    private void applyAlterConstraints(String text, List<ParsedTable> tables) {
        Map<String, ParsedTable> byName = new LinkedHashMap<>();
        tables.forEach(t -> byName.put(t.name(), t));

        Matcher pk = ALTER_PK.matcher(text);
        while (pk.find()) {
            ParsedTable table = byName.get(pk.group(1));
            if (table == null) {
                continue;
            }
            for (String col : identifiers(pk.group(2))) {
                flagColumn(table, col, "PK");
            }
        }
        for (Pattern p : List.of(ALTER_UK, UNIQUE_INDEX)) {
            Matcher uk = p.matcher(text);
            while (uk.find()) {
                ParsedTable table = byName.get(uk.group(1));
                List<String> cols = identifiers(uk.group(2));
                if (table != null && cols.size() == 1) {
                    flagColumn(table, cols.get(0), "UK");
                }
            }
        }
    }

    /** COMMENT ON TABLE/COLUMN(PostgreSQL) 을 코멘트가 비어 있는 자리에 채워 넣는다. */
    private List<ParsedTable> applyCommentOn(String text, List<ParsedTable> tables) {
        Map<String, String> tableComments = new LinkedHashMap<>();
        Matcher tc = COMMENT_ON_TABLE.matcher(text);
        while (tc.find()) {
            tableComments.put(tc.group(1), unescape(tc.group(2)));
        }
        Map<String, Map<String, String>> columnComments = new LinkedHashMap<>();
        Matcher cc = COMMENT_ON_COLUMN.matcher(text);
        while (cc.find()) {
            columnComments.computeIfAbsent(cc.group(1), k -> new LinkedHashMap<>())
                    .put(cc.group(2), unescape(cc.group(3)));
        }
        if (tableComments.isEmpty() && columnComments.isEmpty()) {
            return tables;
        }

        List<ParsedTable> out = new ArrayList<>();
        for (ParsedTable t : tables) {
            Map<String, String> byCol = columnComments.getOrDefault(t.name(), Map.of());
            List<ParsedColumn> cols = t.columns();
            if (!byCol.isEmpty()) {
                cols = new ArrayList<>();
                for (ParsedColumn c : t.columns()) {
                    String comment = byCol.get(c.name());
                    cols.add(comment == null ? c : new ParsedColumn(c.name(), c.colType(), comment, c.flags()));
                }
            }
            String comment = tableComments.getOrDefault(t.name(), t.comment());
            out.add(new ParsedTable(t.name(), comment, cols));
        }
        return out;
    }

    private void applyFkFlags(List<ParsedTable> tables, List<ParsedRelation> relations) {
        Map<String, ParsedTable> byName = new LinkedHashMap<>();
        tables.forEach(t -> byName.put(t.name(), t));
        for (ParsedRelation r : relations) {
            ParsedTable child = byName.get(r.child());
            if (child == null) {
                continue;
            }
            child.columns().stream()
                    .filter(c -> c.name().equals(r.childCol()))
                    .forEach(c -> c.flags().add("FK"));
        }
    }

    private List<ParsedRelation> dedupe(List<ParsedRelation> relations) {
        Set<String> seen = new LinkedHashSet<>();
        List<ParsedRelation> out = new ArrayList<>();
        for (ParsedRelation r : relations) {
            if (seen.add(r.child() + "→" + r.parent() + "." + r.childCol())) {
                out.add(r);
            }
        }
        return out;
    }

    private void flag(Map<String, ParsedColumn> columns, String colName, String flag) {
        ParsedColumn col = columns.get(colName);
        if (col != null) {
            col.flags().add(flag);
        }
    }

    private void flagColumn(ParsedTable table, String colName, String flag) {
        table.columns().stream()
                .filter(c -> c.name().equals(colName))
                .forEach(c -> c.flags().add(flag));
    }

    /**
     * 타입 정규화 — int(11)→int, unsigned→"uns" 축약.
     * PostgreSQL: character varying→varchar, double precision→double,
     * serial 계열→정수 타입, with/without time zone→tz 접미사/제거.
     * Smart Query 임포트(카탈로그 타입 문자열)도 같은 규칙을 쓴다.
     */
    public static String normalizeType(String rawType, boolean unsigned) {
        String type = rawType.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        type = type.replace("character varying", "varchar")
                .replace("double precision", "double")
                .replaceAll("\\s*without\\s+time\\s+zone", "")
                .replaceAll("\\s*with\\s+time\\s+zone", "tz");
        type = type.replaceAll("\\s+", "");
        String base = type.replaceAll("\\(.*", "");
        if (INT_TYPES.contains(base)) {
            type = base; // 표시 길이는 의미가 없어 제거
        } else if ("serial".equals(base) || "serial4".equals(base)) {
            type = "int";
        } else if ("bigserial".equals(base) || "serial8".equals(base)) {
            type = "bigint";
        } else if ("smallserial".equals(base) || "serial2".equals(base)) {
            type = "smallint";
        }
        return unsigned ? type + " uns" : type;
    }

    /** 주석 제거: -- 행, # 행, 블록 주석. 문자열 리터럴 내부는 건드리지 않도록 행 단위 처리. */
    private String stripComments(String ddl) {
        String noBlock = ddl.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** openIdx 위치의 '(' 와 짝이 되는 ')' 인덱스를 찾는다 (문자열 리터럴 무시). */
    private int findMatchingParen(String text, int openIdx) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\'')) {
                    inString = false;
                } else if (ch == '\'') {
                    i++; // '' 이스케이프
                }
                continue;
            }
            if (ch == '\'') {
                inString = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 최상위 깊이의 콤마로 분리한다 (괄호/문자열 내부 콤마 무시). */
    private List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (inString) {
                cur.append(ch);
                if (ch == '\'' && (i + 1 >= body.length() || body.charAt(i + 1) != '\'')) {
                    inString = false;
                } else if (ch == '\'') {
                    cur.append(body.charAt(++i));
                }
                continue;
            }
            switch (ch) {
                case '\'' -> { inString = true; cur.append(ch); }
                case '(' -> { depth++; cur.append(ch); }
                case ')' -> { depth--; cur.append(ch); }
                case ',' -> {
                    if (depth == 0) {
                        parts.add(cur.toString());
                        cur.setLength(0);
                    } else {
                        cur.append(ch);
                    }
                }
                default -> cur.append(ch);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private List<String> identifiers(String csv) {
        List<String> out = new ArrayList<>();
        for (String token : csv.split(",")) {
            String id = token.trim().replaceAll("[`\"]", "");
            int paren = id.indexOf('('); // key length 표기 제거: col(10)
            if (paren > 0) {
                id = id.substring(0, paren);
            }
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    private String firstIdentifier(String csv) {
        List<String> ids = identifiers(csv);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private String unescape(String s) {
        return s.replace("''", "'");
    }
}
