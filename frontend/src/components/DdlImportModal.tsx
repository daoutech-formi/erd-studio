import { useRef, useState } from "react";
import { importDdl, previewDdl, type DdlImportSummary } from "../api/http";
import { erdSocket } from "../api/socket";
import { useDispatch } from "../state/schemaStore";

interface Props {
  roomId: number;
  onClose: () => void;
}

type Mode = "merge" | "replace";

/** DDL(SQL) 붙여넣기 → 변경 미리보기 → 병합/교체 적용 모달. 현재 방에만 반영된다. */
export function DdlImportModal({ roomId, onClose }: Props) {
  const [ddl, setDdl] = useState("");
  const [mode, setMode] = useState<Mode>("merge");
  const [summary, setSummary] = useState<DdlImportSummary | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const dispatch = useDispatch();

  const loadFile = (file: File | undefined) => {
    if (!file) {
      return;
    }
    file.text().then((text) => {
      setDdl(text);
      setSummary(null);
    });
  };

  const doPreview = () => {
    setBusy(true);
    setError("");
    previewDdl(roomId, ddl, mode)
      .then(setSummary)
      .catch((e: Error) => {
        setSummary(null);
        setError(e.message);
      })
      .finally(() => setBusy(false));
  };

  const doApply = () => {
    const warn = mode === "replace" && summary && summary.removed.length > 0
      ? `⚠ 테이블 ${summary.removed.length}개가 삭제됩니다: ${summary.removed.slice(0, 5).join(", ")}${summary.removed.length > 5 ? " …" : ""}\n`
      : "";
    if (!window.confirm(`${warn}DDL을 ${mode === "merge" ? "병합" : "전체 교체"} 방식으로 적용할까요? (모든 접속자에게 반영)`)) {
      return;
    }
    setBusy(true);
    setError("");
    importDdl(roomId, ddl, mode, erdSocket.currentUser())
      .then((r) => {
        dispatch({
          type: "toast",
          toast: {
            message: `DDL 적용 완료 — 추가 ${r.added.length} · 변경 ${r.updated.length} · 삭제 ${r.removed.length} (총 ${r.tables}개 테이블)`,
            kind: "ok",
          },
        });
        onClose();
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const nameList = (names: string[]) =>
    names.length === 0 ? "없음" : names.slice(0, 12).join(", ") + (names.length > 12 ? ` 외 ${names.length - 12}개` : "");

  return (
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" style={{ width: 560, maxWidth: "94vw" }}>
        <h2>⬆ DDL 불러오기 (SQL)</h2>
        <p>
          MySQL/MariaDB의 <code>CREATE TABLE</code> DDL을 붙여넣으면 파싱해서 스키마에 반영합니다.
          관계는 FOREIGN KEY 제약을 우선 사용하고, 없으면 컬럼명(user_no → donut_user 등)으로 추론합니다.
          도메인은 테이블명과 COMMENT를 분석해 최대 12개로 자동 분류됩니다
          (LLM API 키가 설정된 경우 LLM으로 분류하고, 없으면 키워드 사전 방식으로 동작).
          적용 후에도 변경 이력에서 복원할 수 있습니다.
        </p>
        <textarea
          className="fi"
          rows={10}
          style={{ fontFamily: "Consolas, monospace", fontSize: 12, resize: "vertical" }}
          placeholder={"CREATE TABLE `donut_user` (\n  `user_no` int(11) NOT NULL COMMENT '회원번호',\n  PRIMARY KEY (`user_no`)\n) COMMENT='회원 정보';"}
          value={ddl}
          onChange={(e) => {
            setDdl(e.target.value);
            setSummary(null);
          }}
        />
        <div style={{ display: "flex", gap: 12, alignItems: "center", marginTop: 8, flexWrap: "wrap" }}>
          <button onClick={() => fileInput.current?.click()}>📄 파일 선택</button>
          <input
            ref={fileInput}
            type="file"
            accept=".sql,.txt,.ddl"
            style={{ display: "none" }}
            onChange={(e) => loadFile(e.target.files?.[0])}
          />
          <label style={{ display: "flex", gap: 4, alignItems: "center", cursor: "pointer" }}>
            <input type="radio" checked={mode === "merge"} onChange={() => setMode("merge")} />
            병합 (추가/갱신만, 삭제 없음)
          </label>
          <label style={{ display: "flex", gap: 4, alignItems: "center", cursor: "pointer" }}>
            <input type="radio" checked={mode === "replace"} onChange={() => setMode("replace")} />
            전체 교체 (DDL에 없는 테이블 삭제)
          </label>
        </div>

        {summary && (
          <div className="ddl-preview">
            <div><b>미리보기</b> — 적용 시 총 {summary.tables}개 테이블 · {summary.relations}개 관계</div>
            <div className="add">＋ 추가 {summary.added.length}: {nameList(summary.added)}</div>
            <div className="upd">△ 변경 {summary.updated.length}: {nameList(summary.updated)}</div>
            <div className="del">－ 삭제 {summary.removed.length}: {nameList(summary.removed)}</div>
            {summary.newDomains.length > 0 && (
              <div className="add">◆ 신규 도메인 {summary.newDomains.length}: {nameList(summary.newDomains)}</div>
            )}
            <div className="keep">그대로 {summary.unchanged}개 · 신규 관계 {summary.newRelations}건</div>
          </div>
        )}
        {error && <p style={{ color: "#ff9f9f" }}>{error}</p>}

        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button onClick={doPreview} disabled={busy || ddl.trim() === ""}>
            🔍 미리보기
          </button>
          <button className="primary" onClick={doApply} disabled={busy || summary === null}>
            적용
          </button>
          <button onClick={onClose}>닫기</button>
          {summary === null && ddl.trim() !== "" && (
            <span className="tbnote" style={{ alignSelf: "center" }}>먼저 미리보기로 변경 내용을 확인하세요</span>
          )}
        </div>
      </div>
    </div>
  );
}
