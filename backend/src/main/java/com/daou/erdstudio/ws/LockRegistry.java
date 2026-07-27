package com.daou.erdstudio.ws;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 테이블 단위 소프트 락. table명 → 소유 세션 id. 서버 메모리 전용(단일 인스턴스 전제). */
@Component
public class LockRegistry {

    private final Map<String, String> locks = new ConcurrentHashMap<>();

    /** 비어 있거나 이미 자신이 소유한 경우에만 성공한다. */
    public boolean acquire(String table, String sessionId) {
        return sessionId.equals(locks.compute(table,
                (t, owner) -> owner == null ? sessionId : owner));
    }

    public void release(String table, String sessionId) {
        locks.remove(table, sessionId);
    }

    /** 세션 종료 시 그 세션의 락 전부 해제. 해제된 것이 있으면 true. */
    public boolean releaseAll(String sessionId) {
        return locks.entrySet().removeIf(e -> e.getValue().equals(sessionId));
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(locks);
    }
}
