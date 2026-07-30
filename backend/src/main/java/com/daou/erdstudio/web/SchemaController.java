package com.daou.erdstudio.web;

import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.service.RoomService;
import com.daou.erdstudio.service.SchemaService;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.daou.erdstudio.ws.OpBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 방 단위 스키마 조회/전체 교체 API. */
@RestController
@RequestMapping("/api/rooms/{roomId}")
public class SchemaController {

    private final SchemaService schemaService;
    private final OpService opService;
    private final RoomService roomService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public SchemaController(SchemaService schemaService, OpService opService, RoomService roomService,
                            OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.schemaService = schemaService;
        this.opService = opService;
        this.roomService = roomService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/schema")
    public SchemaDoc schema(@PathVariable Long roomId) {
        roomService.requireExists(roomId);
        return schemaService.loadDoc(roomId);
    }

    /** JSON 불러오기용 전체 교체 — schema.replace op로 기록·브로드캐스트되는 단일 경로를 탄다. */
    @PutMapping("/schema")
    public Map<String, Object> replace(@PathVariable Long roomId, @RequestBody JsonNode doc,
                                       @RequestHeader(value = "X-User", defaultValue = "unknown") String user) {
        roomService.requireExists(roomId);
        Op op = new Op("schema.replace", UserHeader.decode(user),
                objectMapper.createObjectNode().set("doc", doc));
        opService.apply(roomId, op);
        opBroadcaster.broadcastOp(roomId, op);
        return Map.of("ok", true,
                "tables", doc.path("tables").size(),
                "relations", doc.path("relations").size());
    }
}
