// 해시 기반 딥링크 — #/room/:id(?view=readonly). 서버 라우팅 설정 없이 어디서나 동작한다.

export interface Route {
  roomId: number | null;
  readonly: boolean;
}

export function parseRoute(): Route {
  const m = /^#\/room\/(\d+)(?:\?(.*))?$/.exec(location.hash);
  if (!m) {
    return { roomId: null, readonly: false };
  }
  const params = new URLSearchParams(m[2] ?? "");
  return { roomId: Number(m[1]), readonly: params.get("view") === "readonly" };
}

/** 방 진입/이탈에 맞춰 주소를 동기화한다. 히스토리를 쌓지 않도록 replaceState 를 쓴다. */
export function setRoomHash(roomId: number | null, readonly: boolean): void {
  const next = roomId === null ? "" : `#/room/${roomId}${readonly ? "?view=readonly" : ""}`;
  if (location.hash !== next) {
    history.replaceState(null, "", location.pathname + location.search + next);
  }
}

/** 슬랙·위키에 붙여넣는 읽기전용 공유 링크. */
export function readonlyShareUrl(roomId: number): string {
  return `${location.origin}${location.pathname}#/room/${roomId}?view=readonly`;
}
