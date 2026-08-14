import { useState } from "react";
import { saveUser, type UserInfo } from "../state/user";

interface Props {
  onSubmit: (user: UserInfo) => void;
  /** 이름 변경 모드 — 현재 이름을 미리 채우고 취소 버튼을 보여준다. */
  initialName?: string;
  onCancel?: () => void;
}

/** 최초 접속 시 또는 이름 변경 시 사용자 이름(1~20자)을 받는 모달. */
export function NameModal({ onSubmit, initialName, onCancel }: Props) {
  const [name, setName] = useState(initialName ?? "");
  const [error, setError] = useState("");
  const renaming = initialName !== undefined;

  const submit = () => {
    const trimmed = name.trim();
    if (trimmed.length < 1 || trimmed.length > 20) {
      setError("이름은 1~20자로 입력하세요.");
      return;
    }
    onSubmit(saveUser(trimmed));
  };

  return (
    <div className="modal-backdrop">
      <div className="modal">
        <h2>{renaming ? "이름 변경" : "ERD Studio"}</h2>
        <p>
          {renaming
            ? "팀원에게 표시될 이름을 바꿉니다. 방에 입장할 때부터 새 이름이 사용됩니다."
            : "팀원에게 표시될 이름을 입력하세요. 접속자 목록과 편집 중 표시에 사용됩니다."}
        </p>
        <input
          className="fi"
          autoFocus
          value={name}
          maxLength={20}
          placeholder="이름 (1~20자)"
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              submit();
            }
            if (e.key === "Escape" && onCancel) {
              onCancel();
            }
          }}
        />
        {error && <p style={{ color: "#ff9f9f" }}>{error}</p>}
        <div className="modal-actions">
          {onCancel && <button onClick={onCancel}>취소</button>}
          <button className="primary" style={onCancel ? undefined : { width: "100%" }} onClick={submit}>
            {renaming ? "저장" : "시작하기"}
          </button>
        </div>
      </div>
    </div>
  );
}
