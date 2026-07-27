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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 접속 세션 보관 + 브로드캐스트 + heartbeat(15초 ping, 2회 미응답 시 종료). */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private static final long PING_INTERVAL_MS = 15_000;
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        final WebSocketSession session;
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

    public void setUser(String sessionId, String user, String color) {
        Entry entry = entries.get(sessionId);
        if (entry != null) {
            entry.info = new ClientInfo(sessionId, user, color);
        }
    }

    public ClientInfo info(String sessionId) {
        Entry entry = entries.get(sessionId);
        return entry == null ? null : entry.info;
    }

    /** hello를 마친(이름이 있는) 접속자 목록. */
    public List<ClientInfo> users() {
        return entries.values().stream().map(e -> e.info).filter(Objects::nonNull).toList();
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

    public void broadcast(String json) {
        entries.values().forEach(entry -> send(entry, json));
    }

    public void broadcastExcept(String excludeSessionId, String json) {
        entries.forEach((id, entry) -> {
            if (!id.equals(excludeSessionId)) {
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
