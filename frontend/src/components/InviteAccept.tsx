import { useEffect, useState } from "react";
import { acceptInvite, fetchInvitePreview, setProject, type InvitePreview, type Me } from "../api/http";
import { ROLE_LABELS } from "./MembersModal";
import { ThemeToggle } from "./ThemeToggle";

/** 로그인 왕복에서 URL 해시(#/invite/...)가 유실되므로, 로그인 전에 토큰을 여기 보관한다. */
export const PENDING_INVITE_KEY = "erd_pending_invite";

interface Props {
  token: string;
  /** 로그인 상태 — null 이면 아직 조회 전. */
  me: Me | null;
  /** 수락 완료·포기 시 호출 — 상위(App)가 해시를 지우고 방 목록으로 돌려보낸다. */
  onDone: () => void;
}

/** #/invite/{token} 진입 화면 — 초대 내용을 보여주고 로그인/참여를 유도한다. */
export function InviteAccept({ token, me, onDone }: Props) {
  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetchInvitePreview(token)
      .then(setPreview)
      .catch((e: Error) => setError(`초대 정보를 불러오지 못했습니다: ${e.message}`));
  }, [token]);

  const loginThenJoin = () => {
    localStorage.setItem(PENDING_INVITE_KEY, token);
    window.location.href = "/api/auth/oidc/login";
  };

  const join = () => {
    setBusy(true);
    acceptInvite(token)
      .then((result) => {
        setProject(result.projectSlug);
        onDone();
      })
      .catch((e: Error) => setError(`참여에 실패했습니다: ${e.message}`))
      .finally(() => setBusy(false));
  };

  const body = () => {
    if (error) {
      return (
        <>
          <p style={{ marginBottom: 12 }}>{error}</p>
          <button onClick={onDone}>방 목록으로</button>
        </>
      );
    }
    if (!me || !preview) {
      return <p>초대 정보를 확인하는 중…</p>;
    }
    if (!preview.valid) {
      return (
        <>
          <p style={{ marginBottom: 12 }}>{preview.reason ?? "유효하지 않은 초대 링크입니다."}</p>
          <p style={{ marginBottom: 12, opacity: 0.7 }}>새 초대 링크가 필요하면 프로젝트 관리자에게 요청하세요.</p>
          <button onClick={onDone}>방 목록으로</button>
        </>
      );
    }
    if (!me.oidcEnabled) {
      // SSO 미사용 환경 — 어차피 전원 전권이라 수락이 무의미하다.
      return (
        <>
          <p style={{ marginBottom: 12 }}>이 서버는 SSO 를 사용하지 않아 초대 수락이 필요 없습니다.</p>
          <button onClick={onDone}>방 목록으로</button>
        </>
      );
    }
    const roleLabel = preview.role ? ROLE_LABELS[preview.role] : "";
    if (!me.authenticated) {
      return (
        <>
          <p style={{ marginBottom: 12 }}>
            <b>{preview.projectName}</b> 프로젝트에 <b>{roleLabel}</b>(으)로 초대되었습니다.
          </p>
          <button className="primary" onClick={loginThenJoin}>사내 SSO 로 로그인 후 참여</button>
        </>
      );
    }
    if (preview.alreadyMember) {
      return (
        <>
          <p style={{ marginBottom: 12 }}>이미 <b>{preview.projectName}</b> 프로젝트의 멤버입니다.</p>
          <button className="primary" onClick={join} disabled={busy}>프로젝트로 이동</button>
        </>
      );
    }
    return (
      <>
        <p style={{ marginBottom: 12 }}>
          <b>{preview.projectName}</b> 프로젝트에 <b>{roleLabel}</b>(으)로 초대되었습니다.
        </p>
        <button className="primary" onClick={join} disabled={busy}>참여하기</button>{" "}
        <button onClick={onDone} disabled={busy}>취소</button>
      </>
    );
  };

  return (
    <div className="room-screen">
      <header className="room-header">
        <div>
          <h1>ERD Studio</h1>
          <div className="sub">프로젝트 초대</div>
        </div>
        <div className="room-header-right">
          <ThemeToggle />
        </div>
      </header>
      <div className="room-empty">{body()}</div>
    </div>
  );
}
