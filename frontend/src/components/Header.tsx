import { useDispatch, useStore } from "../state/schemaStore";
import { PresenceBar } from "./PresenceBar";
import { SearchBox } from "./SearchBox";
import { ThemeToggle } from "./ThemeToggle";

interface Props {
  roomName: string;
  /** 읽기전용 공유 링크로 들어온 화면 — 편집 토글을 숨긴다. */
  readonly: boolean;
  onShare: () => void;
  onLeaveRoom: () => void;
  onReset: () => void;
  onZoom: (factor: number) => void;
}

/** 상단 헤더 — 방 이름, 검색, 줌 컨트롤, 공유 링크, 편집 모드 토글, 접속자 목록. */
export function Header({ roomName, readonly, onShare, onLeaveRoom, onReset, onZoom }: Props) {
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
          {readonly && <span className="ro-badge">읽기 전용</span>}
        </h1>
        <div className="sub">{subtitle}</div>
      </div>
      <div className="controls">
        <button onClick={onLeaveRoom}>← 방 목록</button>
        <SearchBox />
        <button onClick={onReset}>전체보기</button>
        <button onClick={() => onZoom(1.2)}>+</button>
        <button onClick={() => onZoom(0.83)}>−</button>
        <button onClick={onShare} title="읽기전용 공유 링크 복사">🔗 공유</button>
        <ThemeToggle />
        {!readonly && (
          <button
            className={editMode ? "on" : ""}
            onClick={() => dispatch({ type: "editMode", on: !editMode })}
          >
            {editMode ? "✏ 편집 중 (보기로)" : "✏ 편집 모드"}
          </button>
        )}
        <PresenceBar />
      </div>
    </header>
  );
}
