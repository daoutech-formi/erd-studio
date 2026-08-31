// 해시 기반 딥링크 — #/room/:id(?view=readonly), #/invite/:token. 서버 라우팅 설정 없이 어디서나 동작한다.

export interface Route {
  roomId: number | null;
  readonly: boolean;
  /** #/invite/{token} 로 들어온 초대 토큰. */
  inviteToken: string | null;
}

export function parseRoute(): Route {
  const invite = /^#\/invite\/([0-9a-fA-F]{8,64})$/.exec(location.hash);
  if (invite) {
    return { roomId: null, readonly: false, inviteToken: invite[1] };
  }
  const m = /^#\/room\/(\d+)(?:\?(.*))?$/.exec(location.hash);
  if (!m) {
    return { roomId: null, readonly: false, inviteToken: null };
  }
  const params = new URLSearchParams(m[2] ?? "");
  return { roomId: Number(m[1]), readonly: params.get("view") === "readonly", inviteToken: null };
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

/** 초대받은 사람에게 공유하는 합류 링크. */
export function inviteUrl(token: string): string {
  return `${location.origin}${location.pathname}#/invite/${token}`;
}
