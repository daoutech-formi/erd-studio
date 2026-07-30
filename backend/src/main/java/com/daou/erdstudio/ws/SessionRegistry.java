package com.daou.erdstudio.ws;

import com.daou.erdstudio.ws.dto.ClientInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 접속 세션 보관 + 방별 브로드캐스트 + heartbeat(15초 ping, 2회 미응답 시 종료).
 * 정원과 접속자 목록은 clientKey(브라우저) 기준이므로 같은 브라우저의 여러 탭은 1명으로 센다.
 */
@Component
public class SessionRegistry {

    /** 방 하나에 동시 입장할 수 있는 최대 인원(서로 다른 clientKey 수). */
    public static final int MAX_USERS_PER_ROOM = 10;

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private static final long PING_INTERVAL_MS = 15_000;
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        final WebSocketSession session;
        volatile Long roomId;
        volatile ClientInfo info;
        volatile long lastPong = System.currentTimeMillis();

        Entry(WebSocketSession session) {
            this.session = session;
        }
    }

    public void add(WebSocketSession session) {
        entries.put(session.getId(),
                new Entry(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT)));
    }

    public void remove(String sessionId) {
        entries.remove(sessionId);
    }

    /**
     * 방에 입장시킨다. 방의 서로 다른 clientKey 가 이미 {@value #MAX_USERS_PER_ROOM}개면 false.
     * 자신과 같은 clientKey(같은 브라우저의 다른 탭)가 이미 있으면 정원과 무관하게 허용한다.
     */
    public synchronized boolean joinRoom(String sessionId, Long roomId, String clientKey, String user, String color) {
        Entry entry = entries.get(sessionId);
        if (entry == null || roomId == null) {
            return false;
        }
        Set<String> otherKeys = new HashSet<>();
        entries.forEach((id, e) -> {
            if (!id.equals(sessionId) && e.info != null && roomId.equals(e.roomId)) {
                otherKeys.add(e.info.clientKey());
            }
        });
        if (!otherKeys.contains(clientKey) && otherKeys.size() >= MAX_USERS_PER_ROOM) {
            return false;
        }
        entry.roomId = roomId;
        entry.info = new ClientInfo(sessionId, clientKey, user, color);
        return true;
    }

    public Long roomOf(String sessionId) {
        Entry entry = entries.get(sessionId);
        return entry == null ? null : entry.roomId;
    }

    public ClientInfo info(String sessionId) {
        Entry entry = entries.get(sessionId);
        return entry == null ? null : entry.info;
    }

    /** 해당 방에서 hello를 마친 접속자 목록 — clientKey 당 1명으로 중복을 제거한다. */
    public List<ClientInfo> users(Long roomId) {
        Map<String, ClientInfo> byClientKey = new LinkedHashMap<>();
        if (roomId == null) {
            return List.of();
        }
        for (Entry entry : entries.values()) {
            ClientInfo info = entry.info;
            if (info != null && roomId.equals(entry.roomId)) {
                byClientKey.putIfAbsent(info.clientKey(), info);
            }
        }
        return List.copyOf(byClientKey.values());
    }

    /** 방의 접속 인원 수(서로 다른 clientKey 수). */
    public int userCount(Long roomId) {
        return users(roomId).size();
    }

    public void markPong(String sessionId) {
        Entry entry = entries.get(sessionId);
        if (entry != null) {
            entry.lastPong = System.currentTimeMillis();
        }
    }

    public void sendTo(String sessionId, String json) {
        Entry entry = entries.get(sessionId);
        if (entry != null) {
            send(entry, json);
        }
    }

    public void broadcast(Long roomId, String json) {
        entries.values().forEach(entry -> {
            if (roomId != null && roomId.equals(entry.roomId)) {
                send(entry, json);
            }
        });
    }

    public void broadcastExcept(Long roomId, String excludeSessionId, String json) {
        entries.forEach((id, entry) -> {
            if (!id.equals(excludeSessionId) && roomId != null && roomId.equals(entry.roomId)) {
                send(entry, json);
            }
        });
    }

    private void send(Entry entry, String json) {
        try {
            entry.session.sendMessage(new TextMessage(json));
        } catch (IOException | IllegalStateException e) {
            closeQuietly(entry.session);
        }
    }

    @Scheduled(fixedRate = PING_INTERVAL_MS)
    void heartbeat() {
        long deadline = System.currentTimeMillis() - PING_INTERVAL_MS * 2;
        for (Entry entry : entries.values()) {
            if (entry.lastPong < deadline) {
                log.info("heartbeat 미응답으로 세션 종료: {}", entry.session.getId());
                closeQuietly(entry.session);
                continue;
            }
            try {
                entry.session.sendMessage(new PingMessage());
            } catch (IOException | IllegalStateException e) {
                closeQuietly(entry.session);
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException ignored) {
            // 종료 실패는 무시 — afterConnectionClosed에서 정리된다.
        }
    }
}
