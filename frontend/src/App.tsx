import { useCallback, useEffect, useRef, useState } from "react";
import { fetchSchema } from "./api/http";
import { erdSocket } from "./api/socket";
import { ErdCanvas } from "./canvas/ErdCanvas";
import type { PanZoomApi } from "./canvas/usePanZoom";
import { DetailPanel } from "./components/DetailPanel";
import { EditForm } from "./components/EditForm";
import { Header } from "./components/Header";
import { HistoryPanel } from "./components/HistoryPanel";
import { Legend } from "./components/Legend";
import { NameModal } from "./components/NameModal";
import { Toast } from "./components/Toast";
import { Toolbar } from "./components/Toolbar";
import { useDispatch, useStore } from "./state/schemaStore";
import { undoManager } from "./state/undo";
import { loadUser, type UserInfo } from "./state/user";

export function App() {
  const { editMode, selected, editing, locks, historyOpen } = useStore();
  const dispatch = useDispatch();
  const [user, setUser] = useState<UserInfo | null>(loadUser);
  const panZoomRef = useRef<PanZoomApi | null>(null);

  const resync = useCallback(() => {
    fetchSchema()
      .then((doc) => dispatch({ type: "loaded", doc }))
      .catch((e: Error) => dispatch({ type: "loadError", message: e.message }));
  }, [dispatch]);

  // 이름이 정해지면 스키마 로드 + WebSocket 연결 (재접속 시 전체 재동기화).
  useEffect(() => {
    if (!user) {
      return;
    }
    resync();
    erdSocket.onResync = resync;
    erdSocket.connect(user.name, user.color, dispatch);
  }, [user, dispatch, resync]);

  // 소프트 락 — 편집 대상이 바뀔 때 acquire/release를 한 곳에서 처리한다.
  useEffect(() => {
    if (!user) {
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
  }, [user, editMode, selected, editing, locks, dispatch]);

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
  return (
    <div id="app">
      <Header
        onReset={() => {
          panZoomRef.current?.reset();
          dispatch({ type: "select", name: null });
          dispatch({ type: "search", text: "" });
        }}
        onZoom={(f) => panZoomRef.current?.zoomBy(f)}
      />
      {editMode && <Toolbar />}
      <div id="body">
        <ErdCanvas apiRef={panZoomRef} onNodeClick={onNodeClick} />
        <Legend />
        {historyOpen ? (
          <HistoryPanel />
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
