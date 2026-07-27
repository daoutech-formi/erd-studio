import type { SchemaDoc } from "../types";
import { rowStr } from "../types";
import { downloadText } from "./download";

// 구 구현(exportDBML)과 동일 — dbdiagram.io / ERD Cloud import용.

const noteSafe = (s: string): string => s.replace(/'/g, "");

export function exportDbml(doc: SchemaDoc): void {
  let out = "// ERD Studio export (DBML)\n\n";
  for (const t of doc.tables) {
    const name = rowStr(t, 0);
    out += `Table ${name} {\n`;
    for (const c of doc.columns[name] ?? []) {
      const flag = rowStr(c, 3);
      const settings: string[] = [];
      if (flag.includes("PK")) {
        settings.push("pk");
      }
      if (flag === "UK") {
        settings.push("unique");
      }
      if (rowStr(c, 2)) {
        settings.push(`note: '${noteSafe(rowStr(c, 2))}'`);
      }
      const type = (rowStr(c, 1) || "varchar").split(" ")[0];
      out += `  ${rowStr(c, 0)} ${type}${settings.length > 0 ? ` [${settings.join(", ")}]` : ""}\n`;
    }
    out += `  Note: '${noteSafe(rowStr(t, 2))}'\n}\n\n`;
  }
  for (const r of doc.relations) {
    const child = rowStr(r, 0);
    const parent = rowStr(r, 1);
    const parentPk = (doc.columns[parent] ?? []).find((c) => rowStr(c, 3).includes("PK"));
    const childCols = doc.columns[child] ?? [];
    const childCol = parentPk
      ? childCols.find((c) => rowStr(c, 0) === rowStr(parentPk, 0)) ??
        childCols.find((c) => rowStr(c, 3).includes("FK"))
      : undefined;
    if (childCol && parentPk) {
      out += `Ref: ${child}.${rowStr(childCol, 0)} > ${parent}.${rowStr(parentPk, 0)}\n`;
    }
  }
  downloadText("erd-studio-schema.dbml", out);
}
