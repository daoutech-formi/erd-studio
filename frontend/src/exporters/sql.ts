import type { Row, SchemaDoc } from "../types";
import { rowStr } from "../types";
import { downloadText } from "./download";

// MySQL/MariaDB 또는 PostgreSQL 방언의 CREATE TABLE 스크립트를 생성한다.

export type SqlDialect = "mysql" | "postgres";

const mysqlType = (t: string): string => (t || "varchar(50)").replace(/\buns\b/, "unsigned");
const quoteSafe = (s: string): string => s.replace(/'/g, "");

/** 내부 표기 타입 → PostgreSQL 타입. unsigned 는 PG 에 없으므로 제거한다. */
function pgType(raw: string): string {
  const t = (raw || "varchar(50)").replace(/\s*\buns\b/, "");
  const base = t.replace(/\(.*/, "");
  const paren = t.slice(base.length);
  switch (base) {
    case "tinyint":
    case "mediumint":
      return "smallint";
    case "int":
      return "integer";
    case "datetime":
      return "timestamp";
    case "timestamptz":
      return "timestamptz";
    case "double":
    case "float":
      return "double precision";
    case "mediumtext":
    case "longtext":
    case "tinytext":
      return "text";
    case "blob":
    case "mediumblob":
    case "longblob":
      return "bytea";
    default:
      return base + paren;
  }
}

function fkColumnOf(childCols: Row[], parentPk: Row | undefined): Row | undefined {
  if (parentPk) {
    const sameNameFk = childCols.find(
      (c) => rowStr(c, 0) === rowStr(parentPk, 0) && rowStr(c, 3).includes("FK"),
    );
    if (sameNameFk) {
      return sameNameFk;
    }
    const sameName = childCols.find((c) => rowStr(c, 0) === rowStr(parentPk, 0));
    if (sameName) {
      return sameName;
    }
  }
  return childCols.find((c) => rowStr(c, 3).includes("FK"));
}

export function exportSql(doc: SchemaDoc, dialect: SqlDialect = "mysql"): void {
  const pg = dialect === "postgres";
  const q = (id: string): string => (pg ? `"${id}"` : `\`${id}\``);
  const typeOf = pg ? pgType : mysqlType;

  let out = `-- ERD Studio export (${pg ? "PostgreSQL" : "MySQL/MariaDB"})\n`;
  if (!pg) {
    out += "SET FOREIGN_KEY_CHECKS=0;\n";
  }
  out += "\n";

  const comments: string[] = [];
  for (const t of doc.tables) {
    const name = rowStr(t, 0);
    const cols = doc.columns[name] ?? [];
    out += `CREATE TABLE ${q(name)} (\n`;
    const lines = cols.map((c) => {
      const comment = !pg && rowStr(c, 2) ? ` COMMENT '${quoteSafe(rowStr(c, 2))}'` : "";
      return `  ${q(rowStr(c, 0))} ${typeOf(rowStr(c, 1))}${comment}`;
    });
    const pk = cols.filter((c) => rowStr(c, 3).includes("PK")).map((c) => q(rowStr(c, 0)));
    if (pk.length > 0) {
      lines.push(`  PRIMARY KEY (${pk.join(",")})`);
    }
    const tableComment = quoteSafe(rowStr(t, 2));
    out += `${lines.join(",\n")}\n)${pg ? "" : ` COMMENT='${tableComment}'`};\n\n`;

    if (pg) {
      // PostgreSQL 은 코멘트를 별도 문으로 단다.
      if (tableComment) {
        comments.push(`COMMENT ON TABLE ${q(name)} IS '${tableComment}';`);
      }
      for (const c of cols) {
        if (rowStr(c, 2)) {
          comments.push(`COMMENT ON COLUMN ${q(name)}.${q(rowStr(c, 0))} IS '${quoteSafe(rowStr(c, 2))}';`);
        }
      }
    }
  }
  if (comments.length > 0) {
    out += `-- 코멘트\n${comments.join("\n")}\n\n`;
  }

  out += "-- 외래키 (관계)\n";
  doc.relations.forEach((r, i) => {
    const child = rowStr(r, 0);
    const parent = rowStr(r, 1);
    const parentPk = (doc.columns[parent] ?? []).find((c) => rowStr(c, 3).includes("PK"));
    const childCol = fkColumnOf(doc.columns[child] ?? [], parentPk);
    if (childCol && parentPk) {
      out += `ALTER TABLE ${q(child)} ADD CONSTRAINT ${q(`fk_${child}_${i}`)} ` +
        `FOREIGN KEY (${q(rowStr(childCol, 0))}) REFERENCES ${q(parent)} (${q(rowStr(parentPk, 0))});\n`;
    }
  });
  if (!pg) {
    out += "\nSET FOREIGN_KEY_CHECKS=1;\n";
  }
  downloadText(`erd-studio-schema-${dialect}.sql`, out);
}
