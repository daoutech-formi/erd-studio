import { useCallback, useEffect, useRef, useState } from "react";
import { fetchSchema, type RoomInfo } from "./api/http";
import { erdSocket } from "./api/socket";
import { ErdCanvas } from "./canvas/ErdCanvas";
import type { PanZoomApi } from "./canvas/usePanZoom";
import { DetailPanel } from "./components/DetailPanel";
import { EditForm } from "./components/EditForm";
import { Header } from "./components/Header";
import { HistoryPanel } from "./components/HistoryPanel";
import { Legend } from "./components/Legend";
import { NameModal } from "./components/NameModal";
import { RoomList } from "./components/RoomList";
import { Toast } from "./components/Toast";
import { Toolbar } from "./components/Toolbar";
import { useDispatch, useStore } from "./state/schemaStore";
import { undoManager } from "./state/undo";
import { clientKey, loadUser, type UserInfo } from "./state/user";

export function App() {
  const { editMode, selected, editing, locks, historyOpen } = useStore();
  const dispatch = useDispatch();
  const [user, setUser] = useState<UserInfo | null>(loadUser);
  const [room, setRoom] = useState<RoomInfo | null>(null);
  /** 입장 거절 등으로 방 목록에 돌아왔을 때 보여줄 안내 문구. */
  const [notice, setNotice] = useState("");
  const panZoomRef = useRef<PanZoomApi | null>(null);
  const roomId = room?.id ?? null;

  const leaveRoom = useCallback(() => {
    erdSocket.onResync = null;
    erdSocket.onFatal = null;
    erdSocket.close();
    undoManager.clear();
    dispatch({ type: "reset" });
    setRoom(null);
  }, [dispatch]);

  const enterRoom = useCallback(
    (next: RoomInfo) => {
      setNotice("");
      undoManager.clear();
      dispatch({ type: "reset" });
      setRoom(next);
    },
    [dispatch],
  );

  const resync = useCallback(() => {
    if (roomId === null) {
      return;
    }
    fetchSchema(roomId)
      .then((doc) => dispatch({ type: "loaded", doc }))
      .catch((e: Error) => dispatch({ type: "loadError", message: e.message }));
  }, [dispatch, roomId]);

  // 방에 들어가면 그 방의 스키마 로드 + WebSocket 연결 (재접속 시 전체 재동기화).
  useEffect(() => {
    if (!user || roomId === null) {
      return;
    }
    resync();
    erdSocket.onResync = resync;
    erdSocket.onFatal = (message) => {
      setNotice(message);
      leaveRoom();
    };
    erdSocket.connect(roomId, user.name, user.color, clientKey, dispatch);
  }, [user, roomId, dispatch, resync, leaveRoom]);

  // 소프트 락 — 편집 대상이 바뀔 때 acquire/release를 한 곳에서 처리한다.
  useEffect(() => {
    if (!user || roomId === null) {
      return;
    }
    if (!editMode) {
      if (editing) {
        erdSocket.sendLock("release", editing);
        dispatch({ type: "editing", name: null });
      }
      return;
    }
    if (selected === editing) {
      return;
    }
    if (editing) {
      erdSocket.sendLock("release", editing);
    }
    if (!selected) {
      if (editing) {
        dispatch({ type: "editing", name: null });
      }
      return;
    }
    const lock = locks[selected];
    if (lock) {
      dispatch({ type: "toast", toast: { message: `${lock.user}님이 편집 중입니다.`, kind: "err" } });
      dispatch({ type: "select", name: null });
      dispatch({ type: "editing", name: null });
      return;
    }
    erdSocket.sendLock("acquire", selected);
    dispatch({ type: "editing", name: selected });
  }, [user, roomId, editMode, selected, editing, locks, dispatch]);

  // Ctrl+Z / Ctrl+Shift+Z — 입력 필드에 포커스가 있으면 브라우저 기본 동작을 유지한다.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== "z") {
        return;
      }
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
        return;
      }
      e.preventDefault();
      const ops = e.shiftKey ? undoManager.redo() : undoManager.undo();
      ops?.forEach((op) => erdSocket.sendOp(op));
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const onNodeClick = useCallback(
    (name: string) => dispatch({ type: "select", name }),
    [dispatch],
  );

  if (!user) {
    return <NameModal onSubmit={setUser} />;
  }
  if (!room) {
    return <RoomList user={user} notice={notice} onEnter={enterRoom} />;
  }
  return (
    <div id="app">
      <Header
        roomName={room.name}
        onLeaveRoom={leaveRoom}
        onReset={() => {
          panZoomRef.current?.reset();
          dispatch({ type: "select", name: null });
          dispatch({ type: "search", text: "" });
        }}
        onZoom={(f) => panZoomRef.current?.zoomBy(f)}
      />
      {editMode && <Toolbar roomId={room.id} />}
      <div id="body">
        <ErdCanvas apiRef={panZoomRef} onNodeClick={onNodeClick} />
        <Legend />
        {historyOpen ? (
          <HistoryPanel roomId={room.id} />
        ) : editMode && editing ? (
          <EditForm key={editing} table={editing} />
        ) : (
          <DetailPanel />
        )}
        <Toast />
      </div>
    </div>
  );
}
