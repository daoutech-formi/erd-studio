import { useCallback, useEffect, useRef, useState } from "react";
import { fetchMe, fetchRoom, fetchSchema, logout, setProject, type Me, type RoomInfo } from "./api/http";
import { erdSocket } from "./api/socket";
import { ErdCanvas } from "./canvas/ErdCanvas";
import { wasJustDragged } from "./canvas/useNodeDrag";
import type { PanZoomApi } from "./canvas/usePanZoom";
import { DetailPanel } from "./components/DetailPanel";
import { EditForm } from "./components/EditForm";
import { Header } from "./components/Header";
import { HistoryPanel } from "./components/HistoryPanel";
import { InviteAccept, PENDING_INVITE_KEY } from "./components/InviteAccept";
import { Legend } from "./components/Legend";
import { NameModal } from "./components/NameModal";
import { RoomList } from "./components/RoomList";
import { Toast } from "./components/Toast";
import { Toolbar } from "./components/Toolbar";
import { parseRoute, readonlyShareUrl, setRoomHash } from "./state/route";
import { useDispatch, useStore } from "./state/schemaStore";
import { undoManager } from "./state/undo";
import { clientKey, colorFor, loadUser, saveUser, type UserInfo } from "./state/user";
import { copyText } from "./utils/clipboard";

/** SSO 콜백이 ?login_error= 로 알려주는 실패 코드. */
const LOGIN_ERRORS: Record<string, string> = {
  state: "로그인 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요.",
  exchange: "SSO 서버와 통신에 실패했습니다. 잠시 후 다시 시도해 주세요.",
  profile: "SSO 신원 조회에 실패했습니다. 잠시 후 다시 시도해 주세요.",
  username: "SSO 계정의 아이디를 사용할 수 없습니다. 관리자에게 문의하세요.",
  conflict: "이미 다른 SSO 계정에 연결된 아이디입니다. 관리자에게 문의하세요.",
};

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
  /** 로그인 상태 — null 이면 아직 조회 전. 로그인돼 있으면 표시이름이 user.name 을 대체한다. */
  const [me, setMe] = useState<Me | null>(null);
  const [room, setRoom] = useState<RoomInfo | null>(null);
  /** 입장 거절 등으로 방 목록에 돌아왔을 때 보여줄 안내 문구. */
  const [notice, setNotice] = useState("");
  /** 읽기전용 링크로 들어왔는지 — 편집 UI 전체를 숨긴다. 방을 나가면 해제된다. */
  const [readonly, setReadonly] = useState(() => parseRoute().readonly);
  /** 주소의 #/room/:id — 방 목록을 조회해 해당 방으로 바로 들어간다. */
  const [deepLinkId, setDeepLinkId] = useState<number | null>(() => parseRoute().roomId);
  /** 주소의 #/invite/:token — 초대 수락 화면을 띄운다. */
  const [inviteToken, setInviteToken] = useState<string | null>(() => parseRoute().inviteToken);
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
    (next: RoomInfo, forceReadonly = false) => {
      setNotice("");
      undoManager.clear();
      dispatch({ type: "reset" });
      if (forceReadonly) {
        setReadonly(true);   // 뷰어 권한 — 편집 UI 를 숨긴다(서버도 쓰기를 403 으로 막는다).
      }
      setRoom(next);
    },
    [dispatch],
  );

  // 로그인 상태 조회 + SSO 콜백 실패 코드(?login_error=) 안내. 로그인돼 있으면 SSO 표시이름을 쓴다.
  useEffect(() => {
    const code = new URLSearchParams(window.location.search).get("login_error");
    if (code) {
      setNotice(LOGIN_ERRORS[code] ?? "로그인에 실패했습니다.");
      window.history.replaceState(null, "", window.location.pathname + window.location.hash);
    }
    fetchMe()
      .then((m) => {
        setMe(m);
        if (m.authenticated && m.displayName) {
          setUser(saveUser(m.displayName));
        }
        // 초대 링크에서 로그인하러 갔다 온 경우 — 보관해 둔 토큰으로 수락 화면을 다시 띄운다.
        if (m.authenticated) {
          const pending = localStorage.getItem(PENDING_INVITE_KEY);
          if (pending) {
            localStorage.removeItem(PENDING_INVITE_KEY);
            setInviteToken(pending);
          }
        }
      })
      .catch(() => {});
  }, []);

  const onLogout = useCallback(() => {
    logout()
      .then(() => window.location.reload())
      .catch((e: Error) => setNotice(`로그아웃 실패: ${e.message}`));
  }, []);

  // 딥링크 진입 — id 로 방을 찾아 그 방의 프로젝트로 전환한 뒤 들어간다. 없으면 안내 후 방 목록으로.
  useEffect(() => {
    if (deepLinkId === null || !user || room) {
      return;
    }
    fetchRoom(deepLinkId)
      .then((found) => {
        setProject(found.projectSlug);
        enterRoom(found);
      })
      .catch(() => {
        setNotice("링크의 방을 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.");
        setReadonly(false);
        setRoomHash(null, false);
      })
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

  // 같은 테이블을 다시 클릭하면 선택(강조) 해제 — 메모 클릭 토글과 동일한 규칙.
  // 드래그 직후 발생하는 click 은 무시해야 편집 중인 노드를 옮길 때 폼이 닫히지 않는다.
  const onNodeClick = useCallback(
    (name: string) => {
      if (wasJustDragged()) {
        return;
      }
      dispatch({ type: "select", name: name === selected ? null : name });
    },
    [dispatch, selected],
  );

  // 초대 수락은 이름(user)보다 먼저 — 로그인 사용자는 이름이 계정에서 오므로 NameModal 이 필요 없다.
  if (inviteToken) {
    return (
      <InviteAccept
        token={inviteToken}
        me={me}
        onDone={() => {
          setInviteToken(null);
          setRoomHash(null, false);
        }}
      />
    );
  }
  if (!user) {
    return <NameModal onSubmit={setUser} />;
  }
  if (!room) {
    return (
      <RoomList
        user={user}
        me={me}
        notice={notice}
        onEnter={enterRoom}
        onUserChange={setUser}
        onLogout={onLogout}
      />
    );
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
