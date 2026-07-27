import type { SchemaDoc } from "../types";
import { downloadText } from "./download";

export function exportJson(doc: SchemaDoc): void {
  downloadText("erd-studio-schema.json", JSON.stringify(doc, null, 2), "application/json;charset=utf-8");
}

/** JSON 파일을 골라 파싱한다. 형식이 맞으면 onLoad, 아니면 onError를 부른다. */
export function importJson(onLoad: (doc: SchemaDoc) => void, onError: (message: string) => void): void {
  const input = document.createElement("input");
  input.type = "file";
  input.accept = ".json,application/json";
  input.onchange = () => {
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const doc = JSON.parse(String(reader.result)) as SchemaDoc;
        if (!doc.domains || !doc.tables || !doc.relations || !doc.columns) {
          onError("형식이 올바르지 않습니다. (domains/tables/relations/columns 필요)");
          return;
        }
        onLoad(doc);
      } catch (e) {
        onError(`JSON 파싱 오류: ${(e as Error).message}`);
      }
    };
    reader.readAsText(file);
  };
  input.click();
}
