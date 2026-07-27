import { useState } from "react";
import { saveUser, type UserInfo } from "../state/user";

interface Props {
  onSubmit: (user: UserInfo) => void;
}

/** 최초 접속 시 사용자 이름(1~20자)을 받는 모달. */
export function NameModal({ onSubmit }: Props) {
  const [name, setName] = useState("");
  const [error, setError] = useState("");

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
        <h2>ERD Studio</h2>
        <p>팀원에게 표시될 이름을 입력하세요. 접속자 목록과 편집 중 표시에 사용됩니다.</p>
        <input
          className="fi"
          autoFocus
          value={name}
          maxLength={20}
          placeholder="이름 (1~20자)"
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
        />
        {error && <p style={{ color: "#ff9f9f" }}>{error}</p>}
        <button className="primary" style={{ width: "100%" }} onClick={submit}>
          시작하기
        </button>
      </div>
    </div>
  );
}
