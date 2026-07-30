package com.daou.erdstudio.web;

import com.daou.erdstudio.service.DdlImportService;
import com.daou.erdstudio.service.DdlImportService.ImportPlan;
import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.ws.OpBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** DDL 임포트 API — preview 로 변경 요약을 확인하고 import 로 적용한다. */
@RestController
@RequestMapping("/api/ddl")
public class DdlController {

    public record DdlRequest(String ddl, String mode) {
    }

    private final DdlImportService ddlImportService;
    private final OpService opService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public DdlController(DdlImportService ddlImportService, OpService opService,
                         OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.ddlImportService = ddlImportService;
        this.opService = opService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody DdlRequest req) {
        ImportPlan plan = ddlImportService.plan(req.ddl(), req.mode());
        return summaryJson(plan);
    }

    /** 적용 — schema.replace op 단일 경로로 기록·브로드캐스트된다. */
    @PostMapping("/import")
    public Map<String, Object> importDdl(@RequestBody DdlRequest req,
                                         @RequestHeader(value = "X-User", defaultValue = "unknown") String user) {
        ImportPlan plan = ddlImportService.plan(req.ddl(), req.mode());
        opService.validateDoc(plan.doc());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("doc", objectMapper.valueToTree(plan.doc()));
        Op op = new Op("schema.replace", UserHeader.decode(user), payload);
        opService.apply(op);
        opBroadcaster.broadcastOp(op);
        return summaryJson(plan);
    }

    private Map<String, Object> summaryJson(ImportPlan plan) {
        return Map.of("ok", true,
                "tables", plan.doc().tables().size(),
                "relations", plan.doc().relations().size(),
                "added", plan.summary().added(),
                "updated", plan.summary().updated(),
                "removed", plan.summary().removed(),
                "unchanged", plan.summary().unchanged(),
                "newRelations", plan.summary().newRelations(),
                "newDomains", plan.summary().newDomains());
    }
}
