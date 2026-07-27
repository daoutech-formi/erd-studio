import type { Action } from "../state/schemaStore";
import { undoManager, type UndoEntry } from "../state/undo";
import type { Op, WsIncoming } from "../types";

type DispatchFn = (action: Action) => void;

const RECONNECT_DELAY_MS = 2000;

/** /ws 클라이언트 싱글턴 — 끊기면 2초 간격으로 무한 재접속한다. */
class ErdSocket {
  private ws: WebSocket | null = null;
  private dispatch: DispatchFn = () => undefined;
  private user = "";
  private color = "#4f8cff";
  private shouldRun = false;
  /** 재접속 성공 시 전체 재동기화(GET /api/schema)를 수행할 콜백. */
  onResync: (() => void) | null = null;

  connect(user: string, color: string, dispatch: DispatchFn): void {
    this.user = user;
    this.color = color;
    this.dispatch = dispatch;
    if (this.shouldRun) {
      return; // StrictMode 이중 마운트 등으로 중복 연결하지 않는다.
    }
    this.shouldRun = true;
    this.open(false);
  }

  private open(isReconnect: boolean): void {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws`);
    this.ws = ws;
    ws.onopen = () => {
      this.dispatch({ type: "connected", on: true });
      this.sendRaw({ kind: "hello", user: this.user, color: this.color });
      if (isReconnect) {
        this.onResync?.();
      }
    };
    ws.onmessage = (ev) => this.handle(JSON.parse(String(ev.data)) as WsIncoming);
    ws.onerror = () => ws.close();
    ws.onclose = () => {
      this.dispatch({ type: "connected", on: false });
      if (this.shouldRun) {
        window.setTimeout(() => this.open(true), RECONNECT_DELAY_MS);
      }
    };
  }

  private handle(msg: WsIncoming): void {
    switch (msg.kind) {
      case "presence":
        this.dispatch({ type: "presence", users: msg.users });
        break;
      case "op":
        this.dispatch({ type: "applyOp", op: msg.op });
        break;
      case "locks":
        this.dispatch({ type: "locks", locks: msg.locks });
        break;
      case "move":
        this.dispatch({
          type: "applyOp",
          op: { type: "table.move", user: "", payload: { name: msg.table, x: msg.x, y: msg.y } },
        });
        break;
      case "error":
        undoManager.popLast();
        this.dispatch({ type: "toast", toast: { message: msg.message, kind: "err" } });
        break;
    }
  }

  private sendRaw(obj: unknown): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }

  /** undo 이력 없이 op만 보낸다 (undo/redo 실행 자체에 사용). */
  sendOp(op: Op): void {
    this.sendRaw({ kind: "op", op });
  }

  /** 편집 op 전송 + undo 항목 등록. */
  sendEditOp(op: Op, inverse: Op[]): void {
    if (inverse.length > 0) {
      undoManager.push({ undo: inverse, redo: [op] } satisfies UndoEntry);
    }
    this.sendOp(op);
  }

  sendLock(action: "acquire" | "release", table: string): void {
    this.sendRaw({ kind: "lock", action, table });
  }

  sendMove(table: string, x: number, y: number): void {
    this.sendRaw({ kind: "move", table, x, y });
  }

  currentUser(): string {
    return this.user;
  }
}

export const erdSocket = new ErdSocket();
