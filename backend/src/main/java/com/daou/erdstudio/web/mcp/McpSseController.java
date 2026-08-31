package com.daou.erdstudio.web.mcp;

import com.daou.erdstudio.auth.AuthService;
import com.daou.erdstudio.auth.OidcProperties;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.ForbiddenException;
import com.daou.erdstudio.common.UnauthorizedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 서버 (SSE 트랜스포트, JSON-RPC 2.0) — Claude Code 등 MCP 클라이언트가
 * GET /sse 로 스트림을 연 뒤 POST /message 로 요청을 보내면 응답을 SSE 로 돌려준다.
 * 도구 실행은 {@link McpToolService} 에 위임하며 기존 REST/WS 기능에는 영향을 주지 않는다.
 *
 * 등록: claude mcp add --transport sse erd-studio http://<도메인>/sse
 * ERD_MCP_TOKEN 을 설정하면 Authorization: Bearer <토큰> 헤더를 요구한다(선택).
 */
@RestController
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "erd-studio";
    private static final String SERVER_VERSION = "1.0.0";
    private static final long KEEPALIVE_MS = 15_000;

    /** 서버 토큰(ERD_MCP_TOKEN)으로 들어온 요청의 주체 — 운영·배치용이라 superAdmin 급이다. */
    private static final Principal SERVER_PRINCIPAL = new Principal(null, "mcp-server", true);

    private final McpToolService tools;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final OidcProperties oidcProperties;
    private final String token;
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();

    public McpSseController(McpToolService tools, ObjectMapper objectMapper,
                            AuthService authService, OidcProperties oidcProperties,
                            @Value("${erd.mcp-token:${ERD_MCP_TOKEN:}}") String token) {
        this.tools = tools;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.oidcProperties = oidcProperties;
        this.token = token == null ? "" : token.trim();
    }

    /** 세션 하나 — SSE 전송이 스레드 경쟁으로 섞이지 않도록 동기화한다. */
    private static final class SseSession {
        private final SseEmitter emitter;

        SseSession(SseEmitter emitter) {
            this.emitter = emitter;
        }

        synchronized void sendMessage(String json) throws IOException {
            emitter.send(SseEmitter.event().name("message").data(json));
        }

        synchronized void sendEndpoint(String uri) throws IOException {
            emitter.send(SseEmitter.event().name("endpoint").data(uri));
        }

        synchronized void keepalive() throws IOException {
            emitter.send(SseEmitter.event().comment("keepalive"));
        }
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (resolvePrincipal(auth) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        SseEmitter emitter = new SseEmitter(0L); // 무제한 — keepalive 로 유지
        SseSession session = new SseSession(emitter);
        sessions.put(sessionId, session);
        emitter.onCompletion(() -> sessions.remove(sessionId));
        emitter.onTimeout(() -> sessions.remove(sessionId));
        emitter.onError(e -> sessions.remove(sessionId));
        try {
            session.sendEndpoint("/message?sessionId=" + sessionId);
        } catch (IOException e) {
            sessions.remove(sessionId);
            emitter.completeWithError(e);
        }
        log.info("MCP 세션 연결: {} (활성 {}개)", sessionId, sessions.size());
        return ResponseEntity.ok(emitter);
    }

    @PostMapping("/message")
    public ResponseEntity<Void> message(@RequestParam("sessionId") String sessionId,
                                        @RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody JsonNode body) {
        Principal principal = resolvePrincipal(auth);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SseSession session = sessions.get(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        ObjectNode response = handle(body, principal);
        if (response != null) {
            try {
                session.sendMessage(objectMapper.writeValueAsString(response));
            } catch (IOException e) {
                sessions.remove(sessionId);
            }
        }
        return ResponseEntity.accepted().build();
    }

    /** JSON-RPC 요청 처리. 알림(notification)은 null 을 반환해 응답을 보내지 않는다. */
    private ObjectNode handle(JsonNode req, Principal principal) {
        String method = req.path("method").asText("");
        JsonNode id = req.get("id");
        boolean isNotification = id == null || id.isNull();
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initializeResult();
                case "ping" -> objectMapper.createObjectNode();
                case "tools/list" -> objectMapper.valueToTree(Map.of("tools", tools.definitions()));
                case "tools/call" -> callTool(req.path("params"), principal);
                default -> null;
            };
            if (isNotification) {
                return null; // notifications/initialized 등
            }
            if (result == null) {
                return error(id, -32601, "지원하지 않는 메서드입니다: " + method);
            }
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (Exception e) {
            log.warn("MCP 요청 처리 실패 ({}): {}", method, e.getMessage());
            return isNotification ? null : error(id, -32603, e.getMessage() == null ? "서버 오류" : e.getMessage());
        }
    }

    private JsonNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        return result;
    }

    /**
     * 도구 실행 — 실패는 JSON-RPC 오류가 아니라 MCP 규약대로 isError=true 결과로 돌려준다
     * (모델이 오류 메시지를 읽고 스스로 수정할 수 있게).
     */
    private JsonNode callTool(JsonNode params, Principal principal) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        String text;
        boolean isError = false;
        try {
            text = objectMapper.writeValueAsString(tools.call(name, args, principal));
        } catch (IllegalArgumentException | UnauthorizedException | ForbiddenException e) {
            // 권한·입력 오류는 모델이 읽고 사용자에게 설명하도록 isError 결과로 돌려준다.
            text = e.getMessage();
            isError = true;
        } catch (Exception e) {
            log.error("MCP 도구 실행 실패: {}", name, e);
            text = "서버 오류로 실행하지 못했습니다.";
            isError = true;
        }
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        result.putArray("content").add(content);
        result.put("isError", isError);
        return result;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    /**
     * Bearer → 주체. ① 서버 토큰(ERD_MCP_TOKEN) = superAdmin 급 ② 개인 MCP 토큰 = 그 계정
     * ③ SSO 미사용 + 서버 토큰 미설정 = 기존처럼 개방(게스트도 전권) ④ 그 외 null(401).
     */
    private Principal resolvePrincipal(String authHeader) {
        String bearer = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length()).trim()
                : null;
        if (!token.isEmpty() && token.equals(bearer)) {
            return SERVER_PRINCIPAL;
        }
        Principal personal = authService.resolveMcpToken(bearer);
        if (personal != null) {
            return personal;
        }
        if (!oidcProperties.isEnabled() && token.isEmpty()) {
            return Principal.guest();
        }
        return null;
    }

    /** 프록시(Traefik 등)의 유휴 연결 종료를 막는 keepalive. */
    @Scheduled(fixedRate = KEEPALIVE_MS)
    void keepalive() {
        sessions.forEach((id, session) -> {
            try {
                session.keepalive();
            } catch (IOException | IllegalStateException e) {
                sessions.remove(id);
            }
        });
    }
}
