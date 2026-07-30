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
 * MySQL/MariaDB DDL 텍스트를 파싱해 테이블/컬럼/관계 정보를 추출한다.
 * 지원: CREATE TABLE(백틱/무인용부호), 컬럼 타입·unsigned·COMMENT,
 *       PRIMARY KEY, UNIQUE KEY(단일 컬럼), inline/ALTER TABLE FOREIGN KEY.
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

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_COMMENT = Pattern.compile(
            "COMMENT\\s*=?\\s*'((?:[^']|'')*)'", Pattern.CASE_INSENSITIVE);

    private static final Pattern COLUMN_DEF = Pattern.compile(
            "^[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+([A-Za-z]+(?:\\s*\\([^)]*\\))?)((?:\\s+UNSIGNED)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COLUMN_COMMENT = Pattern.compile(
            "COMMENT\\s+'((?:[^']|'')*)'", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "^PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "^UNIQUE\\s+(?:KEY|INDEX)?\\s*[`\"]?[A-Za-z0-9_]*[`\"]?\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INLINE_FK = Pattern.compile(
            "FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_FK = Pattern.compile(
            "ALTER\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+ADD\\s+(?:CONSTRAINT\\s+[`\"]?[A-Za-z0-9_]+[`\"]?\\s+)?"
                    + "FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    /** 제약절 시작 키워드 — 컬럼 정의와 구분한다. */
    private static final Set<String> CONSTRAINT_STARTERS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK");

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
            throw new IllegalArgumentException("CREATE TABLE 문을 찾지 못했습니다. MySQL/MariaDB DDL인지 확인하세요.");
        }

        Matcher af = ALTER_FK.matcher(text);
        while (af.find()) {
            relations.add(new ParsedRelation(af.group(1), af.group(3),
                    firstIdentifier(af.group(2)), firstIdentifier(af.group(4))));
        }
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
                ParsedColumn col = parseColumn(line);
                if (col != null) {
                    columns.put(col.name(), col);
                }
            }
        }
        Matcher tc = TABLE_COMMENT.matcher(tail);
        String comment = tc.find() ? unescape(tc.group(1)) : "";
        return new ParsedTable(name, comment, new ArrayList<>(columns.values()));
    }

    private ParsedColumn parseColumn(String line) {
        Matcher m = COLUMN_DEF.matcher(line);
        if (!m.find()) {
            return null;
        }
        String type = normalizeType(m.group(2), !m.group(3).isBlank());
        Matcher cm = COLUMN_COMMENT.matcher(line);
        String comment = cm.find() ? unescape(cm.group(1)) : "";
        return new ParsedColumn(m.group(1), type, comment, new LinkedHashSet<>());
    }

    private void parseConstraint(String tableName, String line,
                                 Map<String, ParsedColumn> columns, List<ParsedRelation> relations) {
        Matcher pk = PRIMARY_KEY.matcher(line);
        if (pk.find()) {
            for (String col : identifiers(pk.group(1))) {
                flag(columns, col, "PK");
            }
            return;
        }
        Matcher uk = UNIQUE_KEY.matcher(line);
        if (uk.find()) {
            List<String> cols = identifiers(uk.group(1));
            if (cols.size() == 1) { // 복합 유니크는 단일 컬럼 UK 로 오해될 수 있어 표기 생략
                flag(columns, cols.get(0), "UK");
            }
            return;
        }
        Matcher fk = INLINE_FK.matcher(line);
        if (fk.find()) {
            relations.add(new ParsedRelation(tableName, fk.group(2),
                    firstIdentifier(fk.group(1)), firstIdentifier(fk.group(3))));
        }
        // KEY/INDEX 등 나머지 제약은 무시
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

    /** int(11)→int, bigint(20)→bigint 로 정리하고 unsigned 는 "uns" 로 축약한다. */
    private String normalizeType(String rawType, boolean unsigned) {
        String type = rawType.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String base = type.replaceAll("\\(.*", "");
        if (Set.of("int", "tinyint", "smallint", "mediumint", "bigint").contains(base)) {
            type = base; // 표시 길이는 의미가 없어 제거
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
