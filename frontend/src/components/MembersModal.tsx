import { useEffect, useState } from "react";
import {
  createInvite, fetchAssignableUsers, fetchInvites, fetchMembers, replaceMembers, revokeInvite,
  type AssignableUser, type InviteInfo, type ProjectMemberInfo, type ProjectRole,
} from "../api/http";
import { inviteUrl } from "../state/route";
import { copyText } from "../utils/clipboard";

interface Props {
  slug: string;
  projectName: string;
  onClose: () => void;
  /** 저장 성공 시 상위가 프로젝트 목록을 새로고침하도록. */
  onSaved: () => void;
}

export const ROLE_LABELS: Record<ProjectRole, string> = {
  ADMIN: "관리자",
  EDITOR: "편집자",
  VIEWER: "뷰어",
};

/** 프로젝트 멤버 관리 모달(프로젝트 ADMIN 전용) — 역할 변경·제거·계정 추가 후 통째 저장. */
export function MembersModal({ slug, projectName, onClose, onSaved }: Props) {
  const [members, setMembers] = useState<ProjectMemberInfo[]>([]);
  const [assignable, setAssignable] = useState<AssignableUser[]>([]);
  const [addUserId, setAddUserId] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  // 초대 링크 — 멤버 목록과 달리 생성·폐기가 즉시 서버에 적용된다.
  const [invites, setInvites] = useState<InviteInfo[]>([]);
  const [inviteRole, setInviteRole] = useState<ProjectRole>("VIEWER");
  const [inviteDays, setInviteDays] = useState("7");
  const [inviteMax, setInviteMax] = useState("0");
  const [copiedId, setCopiedId] = useState<number | null>(null);

  useEffect(() => {
    Promise.all([fetchMembers(slug), fetchAssignableUsers(slug), fetchInvites(slug)])
      .then(([m, a, i]) => {
        setMembers(m);
        setAssignable(a);
        setInvites(i);
      })
      .catch((e: Error) => setError(`멤버 정보를 불러오지 못했습니다: ${e.message}`));
  }, [slug]);

  const candidates = assignable.filter((u) => !members.some((m) => m.userId === u.id));

  const add = () => {
    const user = candidates.find((u) => String(u.id) === addUserId);
    if (!user) {
      return;
    }
    setMembers([...members, { userId: user.id, username: user.username, displayName: user.displayName, role: "VIEWER" }]);
    setAddUserId("");
  };

  const changeRole = (userId: number, role: ProjectRole) => {
    setMembers(members.map((m) => (m.userId === userId ? { ...m, role } : m)));
  };

  const remove = (userId: number) => {
    setMembers(members.filter((m) => m.userId !== userId));
  };

  const addInvite = () => {
    setBusy(true);
    createInvite(slug, inviteRole, inviteDays === "" ? null : Number(inviteDays), Number(inviteMax))
      .then((created) => setInvites([...invites, created]))
      .catch((e: Error) => setError(`초대 링크 생성 실패: ${e.message}`))
      .finally(() => setBusy(false));
  };

  const copyInvite = (invite: InviteInfo) => {
    copyText(inviteUrl(invite.token)).then((ok) => {
      if (ok) {
        setCopiedId(invite.id);
        window.setTimeout(() => setCopiedId((cur) => (cur === invite.id ? null : cur)), 1500);
      } else {
        setError("링크 복사에 실패했습니다.");
      }
    });
  };

  const removeInvite = (inviteId: number) => {
    setBusy(true);
    revokeInvite(slug, inviteId)
      .then(() => setInvites(invites.filter((i) => i.id !== inviteId)))
      .catch((e: Error) => setError(`초대 폐기 실패: ${e.message}`))
      .finally(() => setBusy(false));
  };

  const save = () => {
    if (!members.some((m) => m.role === "ADMIN")) {
      setError("프로젝트에는 관리자가 1명 이상 있어야 합니다.");
      return;
    }
    setBusy(true);
    replaceMembers(slug, members.map((m) => ({ userId: m.userId, role: m.role })))
      .then(() => {
        onSaved();
        onClose();
      })
      .catch((e: Error) => setError(`저장 실패: ${e.message}`))
      .finally(() => setBusy(false));
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" style={{ width: 460, maxWidth: "94vw" }} onClick={(e) => e.stopPropagation()}>
        <h2>👥 멤버 관리 — {projectName}</h2>
        <p>이 프로젝트를 볼 수 있는 사람과 역할을 정합니다. 관리자는 멤버·프로젝트 관리, 편집자는 ERD 편집, 뷰어는 열람만 할 수 있습니다.</p>

        {members.length === 0 ? (
          <p>아직 멤버가 없습니다. 아래에서 계정을 추가하세요.</p>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 6, marginBottom: 12 }}>
            {members.map((m) => (
              <div key={m.userId} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ flex: 1 }}>
                  {m.displayName ?? "(알 수 없음)"}
                  {m.username && <span style={{ opacity: 0.6 }}> @{m.username}</span>}
                </span>
                <select
                  className="fi"
                  style={{ width: 100, marginBottom: 0 }}
                  value={m.role}
                  onChange={(e) => changeRole(m.userId, e.target.value as ProjectRole)}
                >
                  {(Object.keys(ROLE_LABELS) as ProjectRole[]).map((r) => (
                    <option key={r} value={r}>{ROLE_LABELS[r]}</option>
                  ))}
                </select>
                <button className="mini danger" onClick={() => remove(m.userId)}>제거</button>
              </div>
            ))}
          </div>
        )}

        <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
          <select
            className="fi"
            style={{ flex: 1, marginBottom: 0 }}
            value={addUserId}
            onChange={(e) => setAddUserId(e.target.value)}
          >
            <option value="">＋ 추가할 계정 선택…</option>
            {candidates.map((u) => (
              <option key={u.id} value={u.id}>{u.displayName} @{u.username}</option>
            ))}
          </select>
          <button onClick={add} disabled={!addUserId}>추가</button>
        </div>

        <h3 style={{ margin: "16px 0 6px" }}>🔗 초대 링크</h3>
        <p>링크를 받은 사람이 로그인하면 지정한 역할로 합류합니다. 생성·폐기는 즉시 적용됩니다.</p>

        {invites.length > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 6, marginBottom: 12 }}>
            {invites.map((i) => (
              <div key={i.id} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ flex: 1, opacity: i.exhausted ? 0.5 : 1 }}>
                  {ROLE_LABELS[i.role]}
                  <span style={{ opacity: 0.6 }}>
                    {" · "}
                    {i.expiresAt ? `${new Date(i.expiresAt).toLocaleDateString("ko-KR")}까지` : "무기한"}
                    {" · "}사용 {i.usedCount}/{i.maxUses > 0 ? i.maxUses : "∞"}
                    {i.exhausted && " · 만료됨"}
                  </span>
                </span>
                {!i.exhausted && (
                  <button className="mini" onClick={() => copyInvite(i)}>
                    {copiedId === i.id ? "복사됨!" : "링크 복사"}
                  </button>
                )}
                <button className="mini danger" onClick={() => removeInvite(i.id)} disabled={busy}>폐기</button>
              </div>
            ))}
          </div>
        )}

        <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
          <select
            className="fi"
            style={{ flex: 1, marginBottom: 0 }}
            value={inviteRole}
            onChange={(e) => setInviteRole(e.target.value as ProjectRole)}
          >
            {(Object.keys(ROLE_LABELS) as ProjectRole[]).map((r) => (
              <option key={r} value={r}>{ROLE_LABELS[r]}(으)로 초대</option>
            ))}
          </select>
          <select
            className="fi"
            style={{ width: 92, marginBottom: 0 }}
            value={inviteDays}
            onChange={(e) => setInviteDays(e.target.value)}
          >
            <option value="1">1일</option>
            <option value="7">7일</option>
            <option value="30">30일</option>
            <option value="">무기한</option>
          </select>
          <select
            className="fi"
            style={{ width: 92, marginBottom: 0 }}
            value={inviteMax}
            onChange={(e) => setInviteMax(e.target.value)}
          >
            <option value="0">무제한</option>
            <option value="1">1회</option>
            <option value="5">5회</option>
            <option value="10">10회</option>
          </select>
          <button onClick={addInvite} disabled={busy}>생성</button>
        </div>

        {error && <p style={{ color: "#ff9f9f" }}>{error}</p>}
        <div className="modal-actions">
          <button onClick={onClose}>취소</button>
          <button className="primary" onClick={save} disabled={busy}>저장</button>
        </div>
      </div>
    </div>
  );
}
