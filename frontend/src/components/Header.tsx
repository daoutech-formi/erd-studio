import { useDispatch, useStore } from "../state/schemaStore";
import { PresenceBar } from "./PresenceBar";
import { SearchBox } from "./SearchBox";

interface Props {
  roomName: string;
  onLeaveRoom: () => void;
  onReset: () => void;
  onZoom: (factor: number) => void;
}

/** 상단 헤더 — 방 이름, 검색, 줌 컨트롤, 편집 모드 토글, 접속자 목록. */
export function Header({ roomName, onLeaveRoom, onReset, onZoom }: Props) {
  const { doc, error, editMode } = useStore();
  const dispatch = useDispatch();
  const subtitle = error
    ? `스키마 로드 실패: ${error} (API 서버 상태를 확인하세요)`
    : doc
      ? `총 ${doc.tables.length}개 테이블 · ${doc.relations.length}개 관계 · 편집은 실시간 자동 저장됩니다`
      : "불러오는 중…";

  return (
    <header>
      <div>
        <h1>
          ERD Studio <span className="room-name">{roomName}</span>
        </h1>
        <div className="sub">{subtitle}</div>
      </div>
      <div className="controls">
        <button onClick={onLeaveRoom}>← 방 목록</button>
        <SearchBox />
        <button onClick={onReset}>전체보기</button>
        <button onClick={() => onZoom(1.2)}>+</button>
        <button onClick={() => onZoom(0.83)}>−</button>
        <button
          className={editMode ? "on" : ""}
          onClick={() => dispatch({ type: "editMode", on: !editMode })}
        >
          {editMode ? "✏ 편집 중 (보기로)" : "✏ 편집 모드"}
        </button>
        <PresenceBar />
      </div>
    </header>
  );
}
