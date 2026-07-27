package com.daou.erdstudio.ws;

import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.ws.dto.ClientInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.Map;

/** /ws 엔드포인트 — 메시지 종류(kind)별로 분기만 하고, 검증·반영은 서비스에 위임한다. */
@Component
public class ErdSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ErdSocketHandler.class);

    private final SessionRegistry sessions;
    private final LockRegistry locks;
    private final OpService opService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public ErdSocketHandler(SessionRegistry sessions, LockRegistry locks, OpService opService,
                            OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.locks = locks;
        this.opService = opService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode msg = objectMapper.readTree(message.getPayload());
        switch (msg.path("kind").asText("")) {
            case "hello" -> handleHello(session, msg);
            case "op" -> handleOp(session, msg);
            case "lock" -> handleLock(session, msg);
            case "move" -> handleMove(session, msg);
            default -> sendError(session, "알 수 없는 메시지입니다.");
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        sessions.markPong(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        boolean hadLocks = locks.releaseAll(session.getId());
        sessions.remove(session.getId());
        broadcastPresence();
        if (hadLocks) {
            broadcastLocks();
        }
    }

    private void handleHello(WebSocketSession session, JsonNode msg) throws Exception {
        String user = msg.path("user").asText("").trim();
        if (user.isEmpty() || user.length() > 20) {
            sendError(session, "이름은 1~20자로 입력하세요.");
            return;
        }
        sessions.setUser(session.getId(), user, msg.path("color").asText("#4f8cff"));
        broadcastPresence();
        sessions.sendTo(session.getId(), locksJson());
    }

    private void handleOp(WebSocketSession session, JsonNode msg) throws Exception {
        Op op = objectMapper.treeToValue(msg.path("op"), Op.class);
        try {
            opService.apply(op);
            opBroadcaster.broadcastOp(op);
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
        } catch (Exception e) {
            log.error("op 반영 실패", e);
            sendError(session, "서버 오류로 반영하지 못했습니다.");
        }
    }

    private void handleLock(WebSocketSession session, JsonNode msg) throws Exception {
        String table = msg.path("table").asText("");
        if ("acquire".equals(msg.path("action").asText(""))) {
            locks.acquire(table, session.getId());
        } else {
            locks.release(table, session.getId());
        }
        broadcastLocks();
    }

    private void handleMove(WebSocketSession session, JsonNode msg) throws Exception {
        Map<String, Object> relay = Map.of("kind", "move",
                "table", msg.path("table").asText(""),
                "x", msg.path("x").asDouble(),
                "y", msg.path("y").asDouble(),
                "id", session.getId());
        sessions.broadcastExcept(session.getId(), objectMapper.writeValueAsString(relay));
    }

    private void broadcastPresence() throws Exception {
        sessions.broadcast(objectMapper.writeValueAsString(
                Map.of("kind", "presence", "users", sessions.users())));
    }

    private void broadcastLocks() throws Exception {
        sessions.broadcast(locksJson());
    }

    private String locksJson() throws Exception {
        Map<String, ClientInfo> resolved = new HashMap<>();
        locks.snapshot().forEach((table, sessionId) -> {
            ClientInfo info = sessions.info(sessionId);
            if (info != null) {
                resolved.put(table, info);
            }
        });
        return objectMapper.writeValueAsString(Map.of("kind", "locks", "locks", resolved));
    }

    private void sendError(WebSocketSession session, String message) throws Exception {
        sessions.sendTo(session.getId(),
                objectMapper.writeValueAsString(Map.of("kind", "error", "message", message)));
    }
}
