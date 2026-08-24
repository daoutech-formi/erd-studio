package com.daou.erdstudio.web;

import com.daou.erdstudio.service.DdlImportService;
import com.daou.erdstudio.service.DdlImportService.ImportPlan;
import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.service.RoomService;
import com.daou.erdstudio.service.SmartQueryParser;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.ws.OpBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Smart Query 임포트 API — 추출 쿼리 결과 JSON 을 받아 DDL 임포트와 같은
 * preview → import 흐름으로 처리한다. 추출 쿼리 자체는 프론트엔드가 제공한다.
 */
@RestController
@RequestMapping("/api/rooms/{roomId}/smart")
public class SmartImportController {

    public record SmartRequest(String json, String mode) {
    }

    private final SmartQueryParser smartQueryParser;
    private final DdlImportService ddlImportService;
    private final OpService opService;
    private final RoomService roomService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public SmartImportController(SmartQueryParser smartQueryParser, DdlImportService ddlImportService,
                                 OpService opService, RoomService roomService,
                                 OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.smartQueryParser = smartQueryParser;
        this.ddlImportService = ddlImportService;
        this.opService = opService;
        this.roomService = roomService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@PathVariable Long roomId, @RequestBody SmartRequest req) {
        roomService.requireExists(roomId);
        ImportPlan plan = ddlImportService.plan(roomId, smartQueryParser.parse(req.json()), req.mode());
        return DdlController.summaryJson(plan);
    }

    /** 적용 — schema.replace op 단일 경로로 기록·브로드캐스트된다. */
    @PostMapping("/import")
    public Map<String, Object> importSmart(@PathVariable Long roomId, @RequestBody SmartRequest req,
                                           @RequestHeader(value = "X-User", defaultValue = "unknown") String user) {
        roomService.requireExists(roomId);
        ImportPlan plan = ddlImportService.plan(roomId, smartQueryParser.parse(req.json()), req.mode());
        opService.validateDoc(plan.doc());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("doc", objectMapper.valueToTree(plan.doc()));
        Op op = new Op("schema.replace", UserHeader.decode(user), payload);
        opService.apply(roomId, op);
        opBroadcaster.broadcastOp(roomId, op);
        return DdlController.summaryJson(plan);
    }
}
