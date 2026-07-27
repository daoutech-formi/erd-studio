import type { Row, SchemaDoc } from "../types";
import { rowStr } from "../types";
import { downloadText } from "./download";

// 구 구현(exportSQL)과 동일한 MariaDB CREATE TABLE 생성 규칙.

const mysqlType = (t: string): string => (t || "varchar(50)").replace(/\buns\b/, "unsigned");
const quoteSafe = (s: string): string => s.replace(/'/g, "");

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

export function exportSql(doc: SchemaDoc): void {
  let out = "-- ERD Studio export\nSET FOREIGN_KEY_CHECKS=0;\n\n";
  for (const t of doc.tables) {
    const name = rowStr(t, 0);
    const cols = doc.columns[name] ?? [];
    out += `CREATE TABLE \`${name}\` (\n`;
    const lines = cols.map((c) => {
      const comment = rowStr(c, 2) ? ` COMMENT '${quoteSafe(rowStr(c, 2))}'` : "";
      return `  \`${rowStr(c, 0)}\` ${mysqlType(rowStr(c, 1))}${comment}`;
    });
    const pk = cols.filter((c) => rowStr(c, 3).includes("PK")).map((c) => `\`${rowStr(c, 0)}\``);
    if (pk.length > 0) {
      lines.push(`  PRIMARY KEY (${pk.join(",")})`);
    }
    out += `${lines.join(",\n")}\n) COMMENT='${quoteSafe(rowStr(t, 2))}';\n\n`;
  }
  out += "\n-- 외래키 (관계)\n";
  doc.relations.forEach((r, i) => {
    const child = rowStr(r, 0);
    const parent = rowStr(r, 1);
    const parentPk = (doc.columns[parent] ?? []).find((c) => rowStr(c, 3).includes("PK"));
    const childCol = fkColumnOf(doc.columns[child] ?? [], parentPk);
    if (childCol && parentPk) {
      out += `ALTER TABLE \`${child}\` ADD CONSTRAINT \`fk_${child}_${i}\` ` +
        `FOREIGN KEY (\`${rowStr(childCol, 0)}\`) REFERENCES \`${parent}\` (\`${rowStr(parentPk, 0)}\`);\n`;
    }
  });
  out += "\nSET FOREIGN_KEY_CHECKS=1;\n";
  downloadText("erd-studio-schema.sql", out);
}
