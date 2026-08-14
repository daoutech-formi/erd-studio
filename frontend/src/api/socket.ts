import type { Action } from "../state/schemaStore";
import { undoManager, type UndoEntry } from "../state/undo";
import type { Op, WsIncoming } from "../types";

type DispatchFn = (action: Action) => void;

const RECONNECT_DELAY_MS = 2000;

/** /ws 클라이언트 싱글턴 — 방 단위로 접속하고, 끊기면 2초 간격으로 재접속한다. */
class ErdSocket {
  private ws: WebSocket | null = null;
  private dispatch: DispatchFn = () => undefined;
  private roomId: number | null = null;
  private user = "";
  private color = "#4f8cff";
  private clientKey = "";
  private shouldRun = false;
  /** 연결 세대 — 방을 옮기거나 나갈 때 증가시켜 이전 연결의 재접속 타이머를 무효화한다. */
  private generation = 0;
  /** 재접속 성공 시 전체 재동기화(GET /api/rooms/{id}/schema)를 수행할 콜백. */
  onResync: (() => void) | null = null;
  /** 입장 거절 등 복구 불가 오류 — 방 목록으로 돌아가야 한다. */
  onFatal: ((message: string) => void) | null = null;

  connect(roomId: number, user: string, color: string, clientKey: string, dispatch: DispatchFn): void {
    const roomChanged = this.roomId !== roomId;
    this.roomId = roomId;
    this.user = user;
    this.color = color;
    this.clientKey = clientKey;
    this.dispatch = dispatch;
    if (this.shouldRun && !roomChanged) {
      return; // StrictMode 이중 마운트 등으로 중복 연결하지 않는다.
    }
    if (this.shouldRun) {
      this.close(); // 방이 바뀌면 이전 연결을 끊고 새로 연다.
      this.roomId = roomId;
    }
    this.shouldRun = true;
    this.generation += 1;
    this.open(false, this.generation);
  }

  /** 방을 나갈 때 호출 — 재접속을 멈추고 소켓을 완전히 닫는다. */
  close(): void {
    this.shouldRun = false;
    this.generation += 1;
    const ws = this.ws;
    this.ws = null;
    this.roomId = null;
    if (ws) {
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      ws.close();
    }
    this.dispatch({ type: "connected", on: false });
  }

  private open(isReconnect: boolean, generation: number): void {
    if (!this.shouldRun || generation !== this.generation) {
      return;
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws`);
    this.ws = ws;
    ws.onopen = () => {
      this.dispatch({ type: "connected", on: true });
      this.sendRaw({
        kind: "hello",
        roomId: this.roomId,
        clientKey: this.clientKey,
        user: this.user,
        color: this.color,
      });
      if (isReconnect) {
        this.onResync?.();
      }
    };
    ws.onmessage = (ev) => this.handle(JSON.parse(String(ev.data)) as WsIncoming);
    ws.onerror = () => ws.close();
    ws.onclose = () => {
      this.dispatch({ type: "connected", on: false });
      if (this.shouldRun && generation === this.generation) {
        window.setTimeout(() => this.open(true, generation), RECONNECT_DELAY_MS);
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
        // "memo:{id}" 접두사는 메모 이동 중계 — 서버는 식별자를 그대로 되돌려준다.
        this.dispatch({
          type: "applyOp",
          op: msg.table.startsWith("memo:")
            ? { type: "memo.move", user: "", payload: { id: msg.table.slice(5), x: msg.x, y: msg.y } }
            : { type: "table.move", user: "", payload: { name: msg.table, x: msg.x, y: msg.y } },
        });
        break;
      case "error":
        if (msg.fatal) {
          // 입장 거절 등 — 재접속하지 않고 방 목록으로 돌아간다.
          this.close();
          this.onFatal?.(msg.message);
          break;
        }
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
