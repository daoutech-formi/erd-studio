import { useCallback, useEffect, useState } from "react";
import {
  createProject, createRoom, deleteProject, deleteRoom, fetchProjects, fetchRooms,
  getProject, renameRoomCreator, setProject, type Me, type Project, type RoomInfo,
} from "../api/http";
import { clientKey, type UserInfo } from "../state/user";
import { McpGuideModal } from "./McpGuideModal";
import { MembersModal } from "./MembersModal";
import { NameModal } from "./NameModal";
import { ThemeToggle } from "./ThemeToggle";

const MAX_ROOMS = 20;
const MAX_USERS_PER_ROOM = 10;
const REFRESH_MS = 5000;

interface Props {
  user: UserInfo;
  /** 로그인 상태 — null 이면 아직 조회 전이라 로그인 UI 를 그리지 않는다. */
  me: Me | null;
  /** 입장이 거절되었을 때 서버가 보낸 안내 문구. */
  notice: string;
  /** 뷰어 프로젝트의 방은 forceReadonly=true 로 들어가 편집 UI 를 숨긴다. */
  onEnter: (room: RoomInfo, forceReadonly?: boolean) => void;
  /** 이름 변경 저장 시 새 사용자 정보를 상위(App)에 반영한다. */
  onUserChange: (user: UserInfo) => void;
  onLogout: () => void;
}

/** 메인 화면 — ERD 방 목록. 방을 고르면 그 방의 ERD로 들어간다. */
export function RoomList({ user, me, notice, onEnter, onUserChange, onLogout }: Props) {
  const [rooms, setRooms] = useState<RoomInfo[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  /** 현재 선택된 프로젝트 slug — API 요청 헤더(X-Project-Id)와 항상 함께 바꾼다. */
  const [projectSlug, setProjectSlug] = useState<string>(getProject());
  /** 프로젝트 목록 최초 로드 완료 여부 — 완료 전에는 방 목록을 조회하지 않는다(권한 오류 깜빡임 방지). */
  const [projectsReady, setProjectsReady] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [mcpOpen, setMcpOpen] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);

  const currentProject = projects.find((p) => p.slug === projectSlug) ?? null;
  /** SSO 켠 환경에서 현재 프로젝트의 내 역할이 뷰어면 쓰기 UI 를 잠근다(서버도 403 으로 막는다). */
  const viewer = Boolean(me?.oidcEnabled && currentProject?.myRole === "VIEWER");
  const projectAdmin = Boolean(me?.oidcEnabled && currentProject?.myRole === "ADMIN");

  const load = useCallback(() => {
    fetchRooms()
      .then((list) => {
        setRooms(list);
        setError("");
      })
      .catch((e: Error) => setError(`방 목록을 불러오지 못했습니다: ${e.message}`));
  }, []);

  /** 프로젝트 목록 조회 — 저장된 slug 가 안 보이는(지워진/권한 없는) 프로젝트면 보이는 것으로 되돌린다. */
  const loadProjects = useCallback(() => {
    fetchProjects()
      .then((list) => {
        setProjects(list);
        const stored = getProject();
        const valid = list.find((p) => p.slug === stored)
          ?? list.find((p) => p.slug === "legacy")
          ?? list[0];
        if (valid) {
          setProject(valid.slug);
          setProjectSlug(valid.slug);
        } else {
          setProject("");
          setProjectSlug("");
          setRooms([]);
        }
        setProjectsReady(true);
      })
      .catch(() => {});
  }, []);

  useEffect(loadProjects, [loadProjects]);

  useEffect(() => {
    // 속한 프로젝트가 없으면(비멤버 로그인) 방 목록을 조회하지 않는다 — 서버가 어차피 거절한다.
    if (!projectsReady || (me?.oidcEnabled && projectSlug === "")) {
      return;
    }
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load, projectSlug, projectsReady, me]);

  const changeProject = (slug: string) => {
    setProject(slug);
    setProjectSlug(slug);
  };

  const addProject = () => {
    const projectName = window.prompt("새 프로젝트 이름 (50자 이내)");
    if (!projectName || projectName.trim().length === 0) {
      return;
    }
    setBusy(true);
    createProject(projectName.trim())
      .then((p) => {
        changeProject(p.slug);
        loadProjects();
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const removeProject = () => {
    const current = projects.find((p) => p.slug === projectSlug);
    if (!current || !window.confirm(`'${current.name}' 프로젝트를 삭제할까요? (빈 프로젝트만 삭제됩니다)`)) {
      return;
    }
    setBusy(true);
    deleteProject(projectSlug)
      .then(() => {
        changeProject("legacy");
        loadProjects();
      })
      .catch((e: Error) => setError(`프로젝트 삭제 실패: ${e.message}`))
      .finally(() => setBusy(false));
  };

  const full = rooms.length >= MAX_ROOMS;

  // SSO 가 켜져 있고 미로그인 — 목록 대신 로그인 안내만 보여준다(서버도 어차피 거절한다).
  if (me && me.oidcEnabled && !me.authenticated) {
    return (
      <div className="room-screen">
        <header className="room-header">
          <div>
            <h1>ERD Studio</h1>
            <div className="sub">사내 SSO 로 로그인하면 내가 속한 프로젝트의 ERD 를 볼 수 있습니다.</div>
          </div>
          <div className="room-header-right">
            <ThemeToggle />
          </div>
        </header>
        {notice && <div className="room-notice">{notice}</div>}
        <div className="room-empty">
          <p style={{ marginBottom: 12 }}>로그인이 필요합니다.</p>
          <button
            className="primary"
            onClick={() => { window.location.href = "/api/auth/oidc/login"; }}
          >
            사내 SSO 로 로그인
          </button>
        </div>
      </div>
    );
  }

  const create = () => {
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      setError("방 이름을 입력하세요.");
      return;
    }
    setBusy(true);
    createRoom(trimmed, user.name, clientKey)
      .then((room) => {
        setName("");
        setError("");
        load();
        onEnter(room);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const remove = (room: RoomInfo) => {
    if (!window.confirm(`'${room.name}' 방을 삭제할까요?\n방의 테이블·관계·변경 이력이 모두 사라지며 되돌릴 수 없습니다.`)) {
      return;
    }
    setBusy(true);
    deleteRoom(room.id)
      .then(load)
      .catch((e: Error) => setError(`삭제 실패: ${e.message}`))
      .finally(() => setBusy(false));
  };

  return (
    <div className="room-screen">
      <header className="room-header">
        <div>
          <h1>ERD Studio</h1>
          <div className="sub">
            {user.name}님, 참여할 방을 선택하세요. 방은 최대 {MAX_ROOMS}개, 한 방에 최대 {MAX_USERS_PER_ROOM}명까지
            동시 접속할 수 있습니다.{" "}
            {/* SSO 로그인 상태면 이름은 계정을 따르므로 수동 변경 버튼을 숨긴다. */}
            {!me?.authenticated && (
              <button className="mini" onClick={() => setRenaming(true)}>✏ 이름 변경</button>
            )}
          </div>
        </div>
        <div className="room-header-right">
          <span
            className="tooltip-wrap"
            data-tip={"더 정확한 도메인 분류와 관계 추출이 필요하다면 Claude를 연결해 보세요. "
              + "DDL 임포트부터 의미 기반 도메인 분류까지 대화 한 문장으로 처리됩니다. "
              + "(Claude Code 사용자 한정 · 최초 1회 등록)"}
          >
            <button onClick={() => setMcpOpen(true)}>🔗 MCP 연결</button>
          </span>
          <ThemeToggle />
          <span className="room-count">{rooms.length} / {MAX_ROOMS} 방</span>
          {me?.authenticated ? (
            <button className="mini" onClick={onLogout} title={`${me.displayName} 계정에서 로그아웃`}>
              로그아웃
            </button>
          ) : me?.oidcEnabled ? (
            <button
              className="primary"
              onClick={() => { window.location.href = "/api/auth/oidc/login"; }}
              title="사내 SSO(authentik)로 로그인"
            >
              로그인
            </button>
          ) : null}
        </div>
      </header>

      {notice && <div className="room-notice">{notice}</div>}
      {error && <div className="room-error">{error}</div>}

      <div className="room-create">
        <select
          className="fi"
          style={{ maxWidth: 280 }}
          value={projectSlug}
          disabled={busy}
          onChange={(e) => changeProject(e.target.value)}
          title="프로젝트를 고르면 그 프로젝트의 방만 보입니다"
        >
          {projects.map((p) => (
            <option key={p.slug} value={p.slug}>
              📁 {p.name}
            </option>
          ))}
        </select>
        <button onClick={addProject} disabled={busy}>＋ 프로젝트</button>
        {projectAdmin && (
          <button onClick={() => setMembersOpen(true)} disabled={busy}>👥 멤버 관리</button>
        )}
        {projectSlug !== "legacy" && projectSlug !== "" && rooms.length === 0 && !viewer && (
          <button className="mini danger" onClick={removeProject} disabled={busy}>
            프로젝트 삭제
          </button>
        )}
        {viewer && <span className="room-hint">뷰어 권한 — 열람만 가능합니다.</span>}
      </div>

      {projectsReady && me?.oidcEnabled && projects.length === 0 && (
        <div className="room-empty">
          아직 속한 프로젝트가 없습니다. 위의 ＋ 프로젝트로 직접 만들거나, 프로젝트 관리자에게 초대를 요청하세요.
        </div>
      )}

      {!viewer && projectSlug !== "" && (
        <div className="room-create">
          <input
            className="fi"
            value={name}
            maxLength={30}
            placeholder="새 방 이름 (30자 이내)"
            disabled={full || busy}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && !full && !busy && create()}
          />
          <button className="primary" onClick={create} disabled={full || busy}>
            ＋ 방 만들기
          </button>
          {full && <span className="room-hint">방이 {MAX_ROOMS}개로 가득 찼습니다. 사용하지 않는 방을 삭제하세요.</span>}
        </div>
      )}

      {rooms.length === 0 ? (
        <div className="room-empty">아직 만들어진 방이 없습니다. 첫 방을 만들어 보세요.</div>
      ) : (
        <div className="room-grid">
          {rooms.map((room) => (
            <div
              key={room.id}
              className="room-card"
              role="button"
              tabIndex={0}
              onClick={() => onEnter(room, viewer)}
              onKeyDown={(e) => e.key === "Enter" && onEnter(room, viewer)}
            >
              <div className="rc-top">
                <span className="rc-name">{room.name}</span>
                {!viewer && (
                  <button
                    className="mini danger"
                    title="방 삭제"
                    onClick={(e) => {
                      e.stopPropagation();
                      remove(room);
                    }}
                  >
                    삭제
                  </button>
                )}
              </div>
              <div className="rc-badges">
                <span className="rc-badge">테이블 {room.tableCount}개</span>
                <span className={`rc-badge${room.userCount > 0 ? " on" : ""}`}>
                  접속자 {room.userCount} / {MAX_USERS_PER_ROOM}
                </span>
              </div>
              <div className="rc-meta">
                생성자 {room.createdBy} · {new Date(room.createdAt).toLocaleDateString("ko-KR")}
              </div>
            </div>
          ))}
        </div>
      )}
      {mcpOpen && <McpGuideModal onClose={() => setMcpOpen(false)} />}
      {membersOpen && currentProject && (
        <MembersModal
          slug={currentProject.slug}
          projectName={currentProject.name}
          onClose={() => setMembersOpen(false)}
          onSaved={loadProjects}
        />
      )}
      {renaming && (
        <NameModal
          initialName={user.name}
          onCancel={() => setRenaming(false)}
          onSubmit={(next) => {
            onUserChange(next);
            setRenaming(false);
            // 내가 만든 방들의 생성자 표시명도 새 이름으로 갱신한 뒤 목록을 다시 읽는다.
            renameRoomCreator(clientKey, next.name)
              .then(load)
              .catch((e: Error) => setError(`생성자 이름 갱신 실패: ${e.message}`));
          }}
        />
      )}
    </div>
  );
}
