import { useEffect, useState } from "react";
import { erdSocket } from "../api/socket";
import { useDispatch, useStore } from "../state/schemaStore";
import { invertOp } from "../state/undo";
import type { Op } from "../types";
import { MEMO_COLORS, MEMO_DEFAULT_COLOR, docMemos, rowStr } from "../types";

const MAX_TEXT = 500;

interface Props {
  id: string;
  onClose: () => void;
}

/** 메모 내용·색상 편집 모달. 저장/삭제는 op로 전송되어 모든 접속자에게 반영된다. */
export function MemoModal({ id, onClose }: Props) {
  const { doc } = useStore();
  const dispatch = useDispatch();
  const row = doc ? docMemos(doc).find((m) => rowStr(m, 0) === id) : undefined;
  const [text, setText] = useState(row ? rowStr(row, 1) : "");
  const [color, setColor] = useState(row ? rowStr(row, 4) || MEMO_DEFAULT_COLOR : MEMO_DEFAULT_COLOR);

  // 편집 중 다른 사용자가 이 메모를 지우면 모달을 닫는다.
  useEffect(() => {
    if (!row) {
      onClose();
    }
  }, [row, onClose]);

  if (!doc || !row) {
    return null;
  }
  const user = erdSocket.currentUser();

  const save = () => {
    const op: Op = { type: "memo.apply", user, payload: { id, text, color } };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
    onClose();
  };

  const remove = () => {
    if (!window.confirm("이 메모를 삭제할까요?")) {
      return;
    }
    const op: Op = { type: "memo.delete", user, payload: { id } };
    erdSocket.sendEditOp(op, invertOp(op, doc, user));
    dispatch({ type: "toast", toast: { message: "메모를 삭제했습니다.", kind: "ok" } });
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>메모 편집</h2>
        <p>모든 접속자에게 함께 표시되는 캔버스 메모입니다.</p>
        <textarea
          className="fi memo-ta"
          autoFocus
          value={text}
          maxLength={MAX_TEXT}
          placeholder={`메모 내용 (최대 ${MAX_TEXT}자)`}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && onClose()}
        />
        <div className="memo-swatches">
          {MEMO_COLORS.map((c) => (
            <button
              key={c}
              type="button"
              className={`memo-swatch${c === color ? " on" : ""}`}
              style={{ background: c }}
              title={c}
              onClick={() => setColor(c)}
            />
          ))}
        </div>
        <div className="modal-actions">
          <button className="danger" onClick={remove}>삭제</button>
          <button onClick={onClose}>취소</button>
          <button className="primary" onClick={save}>저장</button>
        </div>
      </div>
    </div>
  );
}
