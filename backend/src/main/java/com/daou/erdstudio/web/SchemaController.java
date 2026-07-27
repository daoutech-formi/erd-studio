package com.daou.erdstudio.web;

import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.service.SchemaService;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.daou.erdstudio.ws.OpBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SchemaController {

    private final SchemaService schemaService;
    private final OpService opService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public SchemaController(SchemaService schemaService, OpService opService,
                            OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.schemaService = schemaService;
        this.opService = opService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @GetMapping("/schema")
    public SchemaDoc schema() {
        return schemaService.loadDoc();
    }

    /** JSON 불러오기용 전체 교체 — schema.replace op로 기록·브로드캐스트되는 단일 경로를 탄다. */
    @PutMapping("/schema")
    public Map<String, Object> replace(@RequestBody JsonNode doc,
                                       @RequestHeader(value = "X-User", defaultValue = "unknown") String user) {
        Op op = new Op("schema.replace", user, objectMapper.createObjectNode().set("doc", doc));
        opService.apply(op);
        opBroadcaster.broadcastOp(op);
        return Map.of("ok", true,
                "tables", doc.path("tables").size(),
                "relations", doc.path("relations").size());
    }
}
