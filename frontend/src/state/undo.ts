import type { Op, Row, SchemaDoc } from "../types";
import { rowBool, rowNum, rowStr } from "../types";

// Undo/Redo — 자신이 보낸 op의 역연산을 쌓아 두고, 실행 시 일반 op로 서버에 보낸다.

export interface UndoEntry {
  undo: Op[];
  redo: Op[];
}

const MAX_DEPTH = 50;

export class UndoManager {
  private undoStack: UndoEntry[] = [];
  private redoStack: UndoEntry[] = [];

  push(entry: UndoEntry): void {
    this.undoStack.push(entry);
    if (this.undoStack.length > MAX_DEPTH) {
      this.undoStack.shift();
    }
    this.redoStack = [];
  }

  /** 서버가 마지막 op를 거부했을 때 대응 항목을 제거한다. */
  popLast(): void {
    this.undoStack.pop();
  }

  undo(): Op[] | null {
    const entry = this.undoStack.pop();
    if (!entry) {
      return null;
    }
    this.redoStack.push(entry);
    if (this.redoStack.length > MAX_DEPTH) {
      this.redoStack.shift();
    }
    return entry.undo;
  }

  redo(): Op[] | null {
    const entry = this.redoStack.pop();
    if (!entry) {
      return null;
    }
    this.undoStack.push(entry);
    return entry.redo;
  }

  clear(): void {
    this.undoStack = [];
    this.redoStack = [];
  }
}

export const undoManager = new UndoManager();

/** op를 보내기 전의 문서를 기준으로 역연산 op 목록을 만든다. */
export function invertOp(op: Op, before: SchemaDoc, user: string): Op[] {
  switch (op.type) {
    case "table.add":
      return [{ type: "table.delete", user, payload: { name: op.payload.name } }];
    case "table.apply": {
      const oldName = String(op.payload.oldName ?? "");
      const row = before.tables.find((t) => rowStr(t, 0) === oldName);
      if (!row) {
        return [];
      }
      const newName = rowStr((op.payload.table as Row) ?? [], 0);
      return [{
        type: "table.apply",
        user,
        payload: {
          oldName: newName,
          table: [oldName, rowStr(row, 1), rowStr(row, 2), rowBool(row, 3)],
          columns: before.columns[oldName] ?? [],
          relations: before.relations.filter((r) => rowStr(r, 0) === oldName),
        },
      }];
    }
    case "table.delete":
      // 삭제는 다른 테이블에서 들어오던 관계까지 걸려 있어 전체 스냅샷으로 되돌린다.
      return [{ type: "schema.replace", user, payload: { doc: before } }];
    case "table.move": {
      const name = String(op.payload.name ?? "");
      const row = before.tables.find((t) => rowStr(t, 0) === name);
      const x = row ? rowNum(row, 4) : null;
      const y = row ? rowNum(row, 5) : null;
      if (x === null || y === null) {
        return [];
      }
      return [{ type: "table.move", user, payload: { name, x, y } }];
    }
    case "domain.apply":
      // 도메인 삭제 시 테이블 소속까지 바뀌므로 전체 스냅샷으로 되돌린다.
      return [{ type: "schema.replace", user, payload: { doc: before } }];
    case "schema.replace":
      return [{ type: "schema.replace", user, payload: { doc: before } }];
  }
}
