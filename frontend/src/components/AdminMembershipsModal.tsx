import { useEffect, useMemo, useState } from "react";
import { fetchAdminMemberships, type AdminMembership } from "../api/http";
import { ROLE_LABELS } from "./MembersModal";

interface Props {
  onClose: () => void;
}

/** 최고관리자 전용 — 모든 프로젝트의 멤버·역할을 한 화면에서 본다(읽기 전용). */
export function AdminMembershipsModal({ onClose }: Props) {
  const [list, setList] = useState<AdminMembership[] | null>(null);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("");

  useEffect(() => {
    fetchAdminMemberships()
      .then(setList)
      .catch((e: Error) => setError(`현황을 불러오지 못했습니다: ${e.message}`));
  }, []);

  // 검색어가 있으면 멤버(이름/아이디) 또는 프로젝트명이 일치하는 것만 남긴다.
  const q = filter.trim().toLowerCase();
  const visible = useMemo(() => {
    if (!list) {
      return [];
    }
    if (!q) {
      return list;
    }
    return list
      .map((p) => (p.name.toLowerCase().includes(q)
        ? p
        : {
          ...p,
          members: p.members.filter((m) =>
            (m.displayName ?? "").toLowerCase().includes(q)
            || (m.username ?? "").toLowerCase().includes(q)),
        }))
      .filter((p) => p.members.length > 0 || p.name.toLowerCase().includes(q));
  }, [list, q]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2>🛡️ 전체 멤버 현황</h2>
        <p>모든 프로젝트의 멤버와 역할입니다(최고관리자 전용 · 읽기 전용). 변경은 해당 프로젝트를 선택한 뒤 멤버 관리에서 하세요.</p>
        <input
          className="fi"
          placeholder="프로젝트명 · 이름 · 아이디 검색"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        {error && <p style={{ color: "var(--err-text)" }}>{error}</p>}
        {list === null && !error && <p>불러오는 중…</p>}
        <div className="admin-projects">
          {visible.map((p) => (
            <div key={p.slug} className="admin-project">
              <div className="admin-project-head">
                <span className="admin-project-name">📁 {p.name}</span>
                <span className="admin-project-meta">방 {p.roomCount}개 · 멤버 {p.members.length}명</span>
              </div>
              {p.members.length === 0 ? (
                <div className="admin-member-empty">등록된 멤버가 없습니다.</div>
              ) : (
                p.members.map((m) => (
                  <div key={m.userId} className="admin-member">
                    <span>
                      {m.displayName ?? "(알 수 없음)"}
                      {m.username && <span className="admin-member-id"> @{m.username}</span>}
                    </span>
                    <span className={`role-badge ${m.role.toLowerCase()}`}>{ROLE_LABELS[m.role]}</span>
                  </div>
                ))
              )}
            </div>
          ))}
          {list !== null && visible.length === 0 && (
            <div className="admin-member-empty">검색 결과가 없습니다.</div>
          )}
        </div>
        <div className="modal-actions">
          <button className="primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  );
}
