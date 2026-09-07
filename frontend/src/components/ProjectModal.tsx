import { useState } from "react";

interface Props {
  onCancel: () => void;
  onSubmit: (name: string) => void;
  busy?: boolean;
}

/** 새 프로젝트 이름을 받는 모달 — window.prompt 대체. */
export function ProjectModal({ onCancel, onSubmit, busy }: Props) {
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  const submit = () => {
    const trimmed = name.trim();
    if (trimmed.length === 0 || trimmed.length > 50) {
      setError("프로젝트 이름은 1~50자로 입력하세요.");
      return;
    }
    onSubmit(trimmed);
  };

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>📁 새 프로젝트</h2>
        <p>프로젝트는 ERD 방의 묶음입니다. 만든 사람이 관리자가 되어 멤버를 초대할 수 있습니다.</p>
        <input
          className="fi"
          autoFocus
          value={name}
          maxLength={50}
          placeholder="프로젝트 이름 (50자 이내)"
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && !busy && submit()}
        />
        {error && <p style={{ color: "var(--err-text)" }}>{error}</p>}
        <div className="modal-actions">
          <button onClick={onCancel}>취소</button>
          <button className="primary" onClick={submit} disabled={busy}>만들기</button>
        </div>
      </div>
    </div>
  );
}
