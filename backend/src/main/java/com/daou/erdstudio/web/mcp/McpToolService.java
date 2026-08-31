package com.daou.erdstudio.web.mcp;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.service.DdlImportService;
import com.daou.erdstudio.service.DdlImportService.ImportPlan;
import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.service.RoomService;
import com.daou.erdstudio.service.SchemaService;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.daou.erdstudio.ws.OpBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 로 노출하는 도구 모음 — 기존 서비스(방/스키마/DDL 임포트)를 그대로 감싼다.
 * 변경 op 는 REST 컨트롤러와 동일하게 schema.replace 단일 경로로 기록·브로드캐스트되므로
 * 접속 중인 브라우저에도 실시간 반영된다.
 */
@Service
public class McpToolService {

    private static final String DEFAULT_USER = "Claude(MCP)";

    private final RoomService roomService;
    private final ErdTableRepository tableRepository;
    private final SchemaService schemaService;
    private final OpService opService;
    private final OpBroadcaster opBroadcaster;
    private final DdlImportService ddlImportService;
    private final ObjectMapper objectMapper;

    public McpToolService(RoomService roomService, ErdTableRepository tableRepository,
                          SchemaService schemaService, OpService opService, OpBroadcaster opBroadcaster,
                          DdlImportService ddlImportService, ObjectMapper objectMapper) {
        this.roomService = roomService;
        this.tableRepository = tableRepository;
        this.schemaService = schemaService;
        this.opService = opService;
        this.opBroadcaster = opBroadcaster;
        this.ddlImportService = ddlImportService;
        this.objectMapper = objectMapper;
    }

    /** tools/list 응답용 도구 정의. */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("list_rooms",
                "ERD 방 목록을 조회한다. 각 방의 id·이름·테이블 수를 반환한다.",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(tool("create_room",
                "새 ERD 방을 만든다 (전체 최대 20개).",
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "방 이름 (30자 이내)"),
                                "user", Map.of("type", "string", "description", "작업자 표시명 (선택)")),
                        "required", List.of("name"))));
        tools.add(tool("get_schema",
                "방의 전체 스키마 문서를 조회한다. domains(도메인), tables([이름, 도메인키, 설명, 허브여부, x, y]), "
                        + "relations([자식, 부모, 라벨?]), columns({테이블명: [[컬럼, 타입, 설명, 플래그?], …]}) 구조다.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "roomId", Map.of("type", "integer", "description", "방 ID")),
                        "required", List.of("roomId"))));
        tools.add(tool("replace_schema",
                "방의 스키마 문서 전체를 교체한다. 도메인 재구성·테이블 도메인 재배치 등에 사용한다. "
                        + "보통 get_schema 로 받은 문서를 수정해 그대로 넣는다. 접속자 전원에게 실시간 반영된다.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "roomId", Map.of("type", "integer", "description", "방 ID"),
                                "doc", Map.of("type", "object", "description", "교체할 스키마 문서(get_schema 와 동일 구조)"),
                                "user", Map.of("type", "string", "description", "작업자 표시명 (선택)")),
                        "required", List.of("roomId", "doc"))));
        tools.add(tool("preview_ddl",
                "DDL(SQL CREATE TABLE)을 파싱해 적용 시 변경 요약(추가/변경/삭제 테이블, 신규 도메인)을 미리 본다. 실제 반영은 하지 않는다.",
                ddlSchema()));
        tools.add(tool("import_ddl",
                "DDL 을 방에 적용한다. mode=merge 는 기존 유지+추가/갱신, replace 는 DDL 에 없는 테이블 삭제. "
                        + "적용 후 get_schema 로 결과를 확인하고, 도메인 분류를 다듬으려면 replace_schema 를 사용한다.",
                ddlSchema()));
        return tools;
    }

    private Map<String, Object> ddlSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "roomId", Map.of("type", "integer", "description", "방 ID"),
                        "ddl", Map.of("type", "string", "description", "CREATE TABLE 문이 담긴 SQL 텍스트"),
                        "mode", Map.of("type", "string", "enum", List.of("merge", "replace"),
                                "description", "병합/전체 교체 (기본 merge)"),
                        "user", Map.of("type", "string", "description", "작업자 표시명 (선택)")),
                "required", List.of("roomId", "ddl"));
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", name);
        def.put("description", description);
        def.put("inputSchema", inputSchema);
        return def;
    }

    /** tools/call 실행. 입력 오류는 IllegalArgumentException(한국어 메시지)으로 던진다. */
    public JsonNode call(String name, JsonNode args) {
        return switch (name) {
            case "list_rooms" -> listRooms();
            case "create_room" -> createRoom(args);
            case "get_schema" -> getSchema(requireRoomId(args));
            case "replace_schema" -> replaceSchema(requireRoomId(args), args);
            case "preview_ddl" -> previewDdl(requireRoomId(args), args);
            case "import_ddl" -> importDdl(requireRoomId(args), args);
            default -> throw new IllegalArgumentException("알 수 없는 도구입니다: " + name);
        };
    }

    private JsonNode listRooms() {
        List<Map<String, Object>> rooms = new ArrayList<>();
        // MCP 는 프로젝트 컨텍스트가 없으므로 전체 방을 보여준다(프로젝트별 제한은 Phase 5).
        for (ErdRoom room : roomService.listAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", room.getId());
            row.put("name", room.getName());
            row.put("createdBy", room.getCreatedBy());
            row.put("tableCount", tableRepository.countByRoomId(room.getId()));
            rooms.add(row);
        }
        return objectMapper.valueToTree(Map.of("rooms", rooms));
    }

    private JsonNode createRoom(JsonNode args) {
        ErdRoom room = roomService.create(args.path("name").asText(""), userOf(args));
        return objectMapper.valueToTree(Map.of("ok", true, "id", room.getId(), "name", room.getName()));
    }

    private JsonNode getSchema(Long roomId) {
        roomService.requireExists(roomId);
        return objectMapper.valueToTree(schemaService.loadDoc(roomId));
    }

    private JsonNode replaceSchema(Long roomId, JsonNode args) {
        roomService.requireExists(roomId);
        JsonNode docNode = args.path("doc");
        if (!docNode.isObject()) {
            throw new IllegalArgumentException("doc(스키마 문서)을 지정하세요.");
        }
        SchemaDoc doc = objectMapper.convertValue(docNode, SchemaDoc.class);
        opService.validateDoc(doc);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("doc", docNode);
        Op op = new Op("schema.replace", userOf(args), payload);
        opService.apply(roomId, op);
        opBroadcaster.broadcastOp(roomId, op);
        return objectMapper.valueToTree(Map.of("ok", true,
                "tables", doc.tables().size(), "relations", doc.relations().size()));
    }

    private JsonNode previewDdl(Long roomId, JsonNode args) {
        roomService.requireExists(roomId);
        ImportPlan plan = ddlImportService.plan(roomId, args.path("ddl").asText(""), modeOf(args));
        return summaryJson(plan);
    }

    private JsonNode importDdl(Long roomId, JsonNode args) {
        roomService.requireExists(roomId);
        ImportPlan plan = ddlImportService.plan(roomId, args.path("ddl").asText(""), modeOf(args));
        opService.validateDoc(plan.doc());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("doc", objectMapper.valueToTree(plan.doc()));
        Op op = new Op("schema.replace", userOf(args), payload);
        opService.apply(roomId, op);
        opBroadcaster.broadcastOp(roomId, op);
        return summaryJson(plan);
    }

    private JsonNode summaryJson(ImportPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tables", plan.doc().tables().size());
        out.put("relations", plan.doc().relations().size());
        out.put("added", plan.summary().added());
        out.put("updated", plan.summary().updated());
        out.put("removed", plan.summary().removed());
        out.put("unchanged", plan.summary().unchanged());
        out.put("newRelations", plan.summary().newRelations());
        out.put("newDomains", plan.summary().newDomains());
        return objectMapper.valueToTree(out);
    }

    private Long requireRoomId(JsonNode args) {
        if (!args.path("roomId").canConvertToLong()) {
            throw new IllegalArgumentException("roomId(방 ID)를 지정하세요. list_rooms 로 확인할 수 있습니다.");
        }
        return args.path("roomId").asLong();
    }

    private String modeOf(JsonNode args) {
        String mode = args.path("mode").asText("merge");
        return "replace".equalsIgnoreCase(mode) ? "replace" : "merge";
    }

    private String userOf(JsonNode args) {
        String user = args.path("user").asText("").trim();
        return user.isEmpty() ? DEFAULT_USER : user;
    }
}
