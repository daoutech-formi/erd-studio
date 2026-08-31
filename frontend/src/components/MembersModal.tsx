import { useEffect, useState } from "react";
import {
  fetchAssignableUsers, fetchMembers, replaceMembers,
  type AssignableUser, type ProjectMemberInfo, type ProjectRole,
} from "../api/http";

interface Props {
  slug: string;
  projectName: string;
  onClose: () => void;
  /** 저장 성공 시 상위가 프로젝트 목록을 새로고침하도록. */
  onSaved: () => void;
}

const ROLE_LABELS: Record<ProjectRole, string> = {
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

  useEffect(() => {
    Promise.all([fetchMembers(slug), fetchAssignableUsers(slug)])
      .then(([m, a]) => {
        setMembers(m);
        setAssignable(a);
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

        {error && <p style={{ color: "#ff9f9f" }}>{error}</p>}
        <div className="modal-actions">
          <button onClick={onClose}>취소</button>
          <button className="primary" onClick={save} disabled={busy}>저장</button>
        </div>
      </div>
    </div>
  );
}
