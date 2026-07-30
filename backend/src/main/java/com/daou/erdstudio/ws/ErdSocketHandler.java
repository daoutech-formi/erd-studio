package com.daou.erdstudio.ws;

import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.service.RoomService;
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

/**
 * /ws 엔드포인트 — 메시지 종류(kind)별로 분기만 하고, 검증·반영은 서비스에 위임한다.
 * hello 로 방에 입장한 뒤에만 op/lock/move 를 처리하며, 모든 브로드캐스트는 그 방 안에서만 이뤄진다.
 */
@Component
public class ErdSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ErdSocketHandler.class);
    private static final int MAX_CLIENT_KEY_LENGTH = 64;

    private final SessionRegistry sessions;
    private final LockRegistry locks;
    private final OpService opService;
    private final RoomService roomService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public ErdSocketHandler(SessionRegistry sessions, LockRegistry locks, OpService opService,
                            RoomService roomService, OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.locks = locks;
        this.opService = opService;
        this.roomService = roomService;
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
        Long roomId = sessions.roomOf(session.getId());
        boolean hadLocks = locks.releaseAll(session.getId());
        sessions.remove(session.getId());
        if (roomId == null) {
            return;
        }
        broadcastPresence(roomId);
        if (hadLocks) {
            broadcastLocks(roomId);
        }
    }

    /** hello: {roomId, clientKey, user, color} — 방 존재·이름 검증 후 정원을 확인하고 입장시킨다. */
    private void handleHello(WebSocketSession session, JsonNode msg) throws Exception {
        String user = msg.path("user").asText("").trim();
        String clientKey = msg.path("clientKey").asText("").trim();
        if (!msg.path("roomId").isNumber()) {
            sendFatal(session, "방 정보가 올바르지 않습니다.");
            return;
        }
        Long roomId = msg.path("roomId").asLong();
        if (user.isEmpty() || user.length() > 20) {
            sendError(session, "이름은 1~20자로 입력하세요.");
            return;
        }
        if (clientKey.isEmpty() || clientKey.length() > MAX_CLIENT_KEY_LENGTH) {
            sendError(session, "브라우저 식별자가 올바르지 않습니다.");
            return;
        }
        try {
            roomService.requireExists(roomId);
        } catch (IllegalArgumentException e) {
            sendFatal(session, e.getMessage());
            return;
        }
        if (!sessions.joinRoom(session.getId(), roomId, clientKey, user, msg.path("color").asText("#4f8cff"))) {
            sendFatal(session, "방 정원(" + SessionRegistry.MAX_USERS_PER_ROOM + "명)이 가득 찼습니다.");
            return;
        }
        broadcastPresence(roomId);
        sessions.sendTo(session.getId(), locksJson(roomId));
    }

    private void handleOp(WebSocketSession session, JsonNode msg) throws Exception {
        Long roomId = sessions.roomOf(session.getId());
        if (roomId == null) {
            sendError(session, "방에 입장한 뒤에 편집할 수 있습니다.");
            return;
        }
        Op op = objectMapper.treeToValue(msg.path("op"), Op.class);
        try {
            opService.apply(roomId, op);
            opBroadcaster.broadcastOp(roomId, op);
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
        } catch (Exception e) {
            log.error("op 반영 실패", e);
            sendError(session, "서버 오류로 반영하지 못했습니다.");
        }
    }

    private void handleLock(WebSocketSession session, JsonNode msg) throws Exception {
        Long roomId = sessions.roomOf(session.getId());
        if (roomId == null) {
            sendError(session, "방에 입장한 뒤에 편집할 수 있습니다.");
            return;
        }
        String table = msg.path("table").asText("");
        if ("acquire".equals(msg.path("action").asText(""))) {
            locks.acquire(roomId, table, session.getId());
        } else {
            locks.release(roomId, table, session.getId());
        }
        broadcastLocks(roomId);
    }

    private void handleMove(WebSocketSession session, JsonNode msg) throws Exception {
        Long roomId = sessions.roomOf(session.getId());
        if (roomId == null) {
            sendError(session, "방에 입장한 뒤에 편집할 수 있습니다.");
            return;
        }
        Map<String, Object> relay = Map.of("kind", "move",
                "table", msg.path("table").asText(""),
                "x", msg.path("x").asDouble(),
                "y", msg.path("y").asDouble(),
                "id", session.getId());
        sessions.broadcastExcept(roomId, session.getId(), objectMapper.writeValueAsString(relay));
    }

    private void broadcastPresence(Long roomId) throws Exception {
        sessions.broadcast(roomId, objectMapper.writeValueAsString(
                Map.of("kind", "presence", "users", sessions.users(roomId))));
    }

    private void broadcastLocks(Long roomId) throws Exception {
        sessions.broadcast(roomId, locksJson(roomId));
    }

    private String locksJson(Long roomId) throws Exception {
        Map<String, ClientInfo> resolved = new HashMap<>();
        locks.snapshot(roomId).forEach((table, sessionId) -> {
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

    /** 복구 불가 오류 — 클라이언트는 방 목록으로 돌아가야 하므로 fatal 표시 후 세션을 닫는다. */
    private void sendFatal(WebSocketSession session, String message) throws Exception {
        sessions.sendTo(session.getId(), objectMapper.writeValueAsString(
                Map.of("kind", "error", "message", message, "fatal", true)));
        session.close(CloseStatus.NORMAL);
    }
}
