package com.daou.erdstudio.ws;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테이블 단위 소프트 락. (방, 테이블명) → 소유 세션 id.
 * 서버 메모리 전용(단일 인스턴스 전제)이며 방이 다르면 같은 테이블명도 서로 간섭하지 않는다.
 */
@Component
public class LockRegistry {

    private record Key(Long roomId, String table) {
    }

    private final Map<Key, String> locks = new ConcurrentHashMap<>();

    /** 비어 있거나 이미 자신이 소유한 경우에만 성공한다. */
    public boolean acquire(Long roomId, String table, String sessionId) {
        return sessionId.equals(locks.compute(new Key(roomId, table),
                (t, owner) -> owner == null ? sessionId : owner));
    }

    public void release(Long roomId, String table, String sessionId) {
        locks.remove(new Key(roomId, table), sessionId);
    }

    /** 세션 종료 시 그 세션의 락 전부 해제. 해제된 것이 있으면 true. */
    public boolean releaseAll(String sessionId) {
        return locks.entrySet().removeIf(e -> e.getValue().equals(sessionId));
    }

    /** 해당 방의 락 현황 (테이블명 → 소유 세션 id). */
    public Map<String, String> snapshot(Long roomId) {
        Map<String, String> out = new HashMap<>();
        locks.forEach((key, sessionId) -> {
            if (key.roomId().equals(roomId)) {
                out.put(key.table(), sessionId);
            }
        });
        return Map.copyOf(out);
    }
}
