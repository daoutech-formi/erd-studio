/**
 * Smart Query 임포트 — DB에 직접 연결하는 대신, 사용자가 자기 DB 클라이언트에서
 * 아래 추출 쿼리를 실행하고 결과 JSON을 붙여넣으면 스키마가 임포트된다.
 * 쿼리는 현재 데이터베이스(스키마)의 테이블·컬럼·PK/UK·FK를 하나의 JSON으로 출력한다.
 * 결과 형식은 백엔드 SmartQueryParser와 맞춰져 있다.
 */

export type SmartDialect = "mysql" | "postgresql";

export const SMART_DIALECT_LABELS: Record<SmartDialect, string> = {
  mysql: "MySQL / MariaDB",
  postgresql: "PostgreSQL",
};

/** MySQL 8+ / MariaDB 10.5+ — 현재 DATABASE() 기준. */
const MYSQL_QUERY = `SELECT JSON_OBJECT(
  'tables', (
    SELECT JSON_ARRAYAGG(JSON_OBJECT(
      'name', t.TABLE_NAME,
      'comment', t.TABLE_COMMENT,
      'columns', (
        SELECT JSON_ARRAYAGG(JSON_OBJECT(
          'name', c.COLUMN_NAME,
          'type', c.COLUMN_TYPE,
          'comment', c.COLUMN_COMMENT,
          'pk', c.COLUMN_KEY = 'PRI',
          'uk', c.COLUMN_KEY = 'UNI'))
        FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME)))
    FROM information_schema.TABLES t
    WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'),
  'relations', (
    SELECT JSON_ARRAYAGG(JSON_OBJECT(
      'child', k.TABLE_NAME, 'childCol', k.COLUMN_NAME,
      'parent', k.REFERENCED_TABLE_NAME, 'parentCol', k.REFERENCED_COLUMN_NAME))
    FROM information_schema.KEY_COLUMN_USAGE k
    WHERE k.TABLE_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_NAME IS NOT NULL)
) AS erd_json;`;

/** PostgreSQL — current_schema()(보통 public) 기준. */
const POSTGRESQL_QUERY = `SELECT json_build_object(
  'tables', (
    SELECT coalesce(json_agg(json_build_object(
      'name', c.relname,
      'comment', coalesce(obj_description(c.oid, 'pg_class'), ''),
      'columns', (
        SELECT coalesce(json_agg(json_build_object(
          'name', a.attname,
          'type', format_type(a.atttypid, a.atttypmod),
          'comment', coalesce(col_description(c.oid, a.attnum), ''),
          'pk', EXISTS (SELECT 1 FROM pg_index i
                        WHERE i.indrelid = c.oid AND i.indisprimary AND a.attnum = ANY(i.indkey)),
          'uk', EXISTS (SELECT 1 FROM pg_index i
                        WHERE i.indrelid = c.oid AND i.indisunique AND NOT i.indisprimary
                          AND array_length(i.indkey, 1) = 1 AND a.attnum = ANY(i.indkey))
        ) ORDER BY a.attnum), '[]'::json)
        FROM pg_attribute a
        WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped)
    ) ORDER BY c.relname), '[]'::json)
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind IN ('r', 'p') AND n.nspname = current_schema()),
  'relations', (
    SELECT coalesce(json_agg(json_build_object(
      'child', child.relname, 'childCol', ca.attname,
      'parent', parent.relname, 'parentCol', pa.attname)), '[]'::json)
    FROM pg_constraint con
    JOIN pg_class child ON child.oid = con.conrelid
    JOIN pg_class parent ON parent.oid = con.confrelid
    JOIN pg_namespace n ON n.oid = child.relnamespace
    JOIN pg_attribute ca ON ca.attrelid = con.conrelid AND ca.attnum = con.conkey[1]
    JOIN pg_attribute pa ON pa.attrelid = con.confrelid AND pa.attnum = con.confkey[1]
    WHERE con.contype = 'f' AND n.nspname = current_schema())
) AS erd_json;`;

export const SMART_QUERIES: Record<SmartDialect, string> = {
  mysql: MYSQL_QUERY,
  postgresql: POSTGRESQL_QUERY,
};
