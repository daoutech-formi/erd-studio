package com.daou.erdstudio.ws;

import com.daou.erdstudio.web.dto.Op;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** DB 반영이 끝난 op를 접속자 전원(발신자 포함)에게 브로드캐스트한다. */
@Component
public class OpBroadcaster {

    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final AtomicLong seq = new AtomicLong();

    public OpBroadcaster(SessionRegistry sessionRegistry, ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    public void broadcastOp(Op op) {
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("kind", "op", "op", op, "seq", seq.incrementAndGet()));
            sessionRegistry.broadcast(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("op 직렬화 실패", e);
        }
    }
}
