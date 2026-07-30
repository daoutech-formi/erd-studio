import { useCallback, useEffect, useState } from "react";
import { createRoom, deleteRoom, fetchRooms, type RoomInfo } from "../api/http";
import type { UserInfo } from "../state/user";

const MAX_ROOMS = 20;
const MAX_USERS_PER_ROOM = 10;
const REFRESH_MS = 5000;

interface Props {
  user: UserInfo;
  /** 입장이 거절되었을 때 서버가 보낸 안내 문구. */
  notice: string;
  onEnter: (room: RoomInfo) => void;
}

/** 메인 화면 — ERD 방 목록. 방을 고르면 그 방의 ERD로 들어간다. */
export function RoomList({ user, notice, onEnter }: Props) {
  const [rooms, setRooms] = useState<RoomInfo[]>([]);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    fetchRooms()
      .then((list) => {
        setRooms(list);
        setError("");
      })
      .catch((e: Error) => setError(`방 목록을 불러오지 못했습니다: ${e.message}`));
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const full = rooms.length >= MAX_ROOMS;

  const create = () => {
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      setError("방 이름을 입력하세요.");
      return;
    }
    setBusy(true);
    createRoom(trimmed, user.name)
      .then((room) => {
        setName("");
        setError("");
        load();
        onEnter(room);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const remove = (room: RoomInfo) => {
    if (!window.confirm(`'${room.name}' 방을 삭제할까요?\n방의 테이블·관계·변경 이력이 모두 사라지며 되돌릴 수 없습니다.`)) {
      return;
    }
    setBusy(true);
    deleteRoom(room.id)
      .then(load)
      .catch((e: Error) => setError(`삭제 실패: ${e.message}`))
      .finally(() => setBusy(false));
  };

  return (
    <div className="room-screen">
      <header className="room-header">
        <div>
          <h1>ERD Studio</h1>
          <div className="sub">
            {user.name}님, 참여할 방을 선택하세요. 방은 최대 {MAX_ROOMS}개, 한 방에 최대 {MAX_USERS_PER_ROOM}명까지
            동시 접속할 수 있습니다.
          </div>
        </div>
        <span className="room-count">{rooms.length} / {MAX_ROOMS} 방</span>
      </header>

      {notice && <div className="room-notice">{notice}</div>}
      {error && <div className="room-error">{error}</div>}

      <div className="room-create">
        <input
          className="fi"
          value={name}
          maxLength={30}
          placeholder="새 방 이름 (30자 이내)"
          disabled={full || busy}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && !full && !busy && create()}
        />
        <button className="primary" onClick={create} disabled={full || busy}>
          ＋ 방 만들기
        </button>
        {full && <span className="room-hint">방이 {MAX_ROOMS}개로 가득 찼습니다. 사용하지 않는 방을 삭제하세요.</span>}
      </div>

      {rooms.length === 0 ? (
        <div className="room-empty">아직 만들어진 방이 없습니다. 첫 방을 만들어 보세요.</div>
      ) : (
        <div className="room-grid">
          {rooms.map((room) => (
            <div
              key={room.id}
              className="room-card"
              role="button"
              tabIndex={0}
              onClick={() => onEnter(room)}
              onKeyDown={(e) => e.key === "Enter" && onEnter(room)}
            >
              <div className="rc-top">
                <span className="rc-name">{room.name}</span>
                <button
                  className="mini danger"
                  title="방 삭제"
                  onClick={(e) => {
                    e.stopPropagation();
                    remove(room);
                  }}
                >
                  삭제
                </button>
              </div>
              <div className="rc-badges">
                <span className="rc-badge">테이블 {room.tableCount}개</span>
                <span className={`rc-badge${room.userCount > 0 ? " on" : ""}`}>
                  접속자 {room.userCount} / {MAX_USERS_PER_ROOM}
                </span>
              </div>
              <div className="rc-meta">
                생성자 {room.createdBy} · {new Date(room.createdAt).toLocaleDateString("ko-KR")}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
