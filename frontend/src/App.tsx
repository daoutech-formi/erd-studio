import { useCallback, useEffect, useRef, useState } from "react";
import { fetchRooms, fetchSchema, type RoomInfo } from "./api/http";
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
import { parseRoute, readonlyShareUrl, setRoomHash } from "./state/route";
import { useDispatch, useStore } from "./state/schemaStore";
import { undoManager } from "./state/undo";
import { clientKey, colorFor, loadUser, type UserInfo } from "./state/user";
import { copyText } from "./utils/clipboard";

/** 읽기전용 링크로 처음 온 사람은 이름 입력 없이 게스트로 들어온다 (localStorage 에는 남기지 않음). */
function initialUser(): UserInfo | null {
  const saved = loadUser();
  if (saved) {
    return saved;
  }
  if (parseRoute().readonly) {
    const name = `게스트${Math.floor(10 + Math.random() * 90)}`;
    return { name, color: colorFor(name) };
  }
  return null;
}

export function App() {
  const { editMode, selected, editing, locks, historyOpen } = useStore();
  const dispatch = useDispatch();
  const [user, setUser] = useState<UserInfo | null>(initialUser);
  const [room, setRoom] = useState<RoomInfo | null>(null);
  /** 입장 거절 등으로 방 목록에 돌아왔을 때 보여줄 안내 문구. */
  const [notice, setNotice] = useState("");
  /** 읽기전용 링크로 들어왔는지 — 편집 UI 전체를 숨긴다. 방을 나가면 해제된다. */
  const [readonly, setReadonly] = useState(() => parseRoute().readonly);
  /** 주소의 #/room/:id — 방 목록을 조회해 해당 방으로 바로 들어간다. */
  const [deepLinkId, setDeepLinkId] = useState<number | null>(() => parseRoute().roomId);
  const panZoomRef = useRef<PanZoomApi | null>(null);
  const roomId = room?.id ?? null;

  const leaveRoom = useCallback(() => {
    erdSocket.onResync = null;
    erdSocket.onFatal = null;
    erdSocket.close();
    undoManager.clear();
    dispatch({ type: "reset" });
    setRoom(null);
    setReadonly(false);
    setRoomHash(null, false);
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

  // 딥링크 진입 — 방 목록에서 id 로 찾아 들어간다. 없으면 안내 후 방 목록으로.
  useEffect(() => {
    if (deepLinkId === null || !user || room) {
      return;
    }
    fetchRooms()
      .then((rooms) => {
        const found = rooms.find((r) => r.id === deepLinkId);
        if (found) {
          enterRoom(found);
        } else {
          setNotice("링크의 방을 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.");
          setReadonly(false);
          setRoomHash(null, false);
        }
      })
      .catch((e: Error) => setNotice(`방 정보를 불러오지 못했습니다: ${e.message}`))
      .finally(() => setDeepLinkId(null));
  }, [deepLinkId, user, room, enterRoom]);

  // 방에 들어가 있는 동안 주소를 #/room/:id 로 유지한다.
  useEffect(() => {
    if (roomId !== null) {
      setRoomHash(roomId, readonly);
    }
  }, [roomId, readonly]);

  const shareRoom = useCallback(() => {
    if (roomId === null) {
      return;
    }
    copyText(readonlyShareUrl(roomId)).then((ok) =>
      dispatch({
        type: "toast",
        toast: ok
          ? { message: "읽기전용 공유 링크가 복사되었습니다.", kind: "ok" }
          : { message: "링크 복사에 실패했습니다.", kind: "err" },
      }),
    );
  }, [roomId, dispatch]);

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
        readonly={readonly}
        onShare={shareRoom}
        onLeaveRoom={leaveRoom}
        onReset={() => {
          panZoomRef.current?.reset();
          dispatch({ type: "select", name: null });
          dispatch({ type: "search", text: "" });
        }}
        onZoom={(f) => panZoomRef.current?.zoomBy(f)}
      />
      {editMode && !readonly && <Toolbar roomId={room.id} />}
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
