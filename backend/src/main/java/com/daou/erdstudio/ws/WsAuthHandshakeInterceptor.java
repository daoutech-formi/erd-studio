package com.daou.erdstudio.ws;

import com.daou.erdstudio.auth.AuthInterceptor;
import com.daou.erdstudio.auth.AuthService;
import com.daou.erdstudio.auth.Principal;
import jakarta.servlet.http.Cookie;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WS 핸드셰이크에서 erd_session 쿠키를 해석해 세션 attributes 에 요청 주체를 싣는다.
 * 여기서 접속을 거부하지는 않는다 — 권한 판정은 방(프로젝트)이 정해지는 hello 에서 한다.
 */
@Component
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    /** WebSocketSession.getAttributes() 에서 Principal 을 꺼내는 키. */
    public static final String ATTR_PRINCIPAL = "erd.principal";

    private final AuthService authService;

    public WsAuthHandshakeInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        attributes.put(ATTR_PRINCIPAL, authService.resolve(readToken(request)));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private static String readToken(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        Cookie[] cookies = servletRequest.getServletRequest().getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AuthInterceptor.COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** attributes 에서 주체를 꺼낸다. 인터셉터를 안 탄 세션(테스트 등)은 게스트로 본다. */
    public static Principal principalOf(Map<String, Object> attributes) {
        Object value = attributes.get(ATTR_PRINCIPAL);
        return value instanceof Principal principal ? principal : Principal.guest();
    }
}
