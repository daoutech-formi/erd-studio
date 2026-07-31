package com.daou.erdstudio.web.mcp;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.service.SchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class McpToolServiceTest {

    @Autowired
    McpToolService toolService;
    @Autowired
    ErdRoomRepository roomRepository;
    @Autowired
    SchemaService schemaService;
    @Autowired
    ObjectMapper objectMapper;

    private Long roomId;

    @BeforeEach
    void createRoom() {
        ErdRoom room = roomRepository.save(new ErdRoom("t_mcp_room", "tester"));
        roomId = room.getId();
    }

    private ObjectNode args() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("roomId", roomId);
        return node;
    }

    @Test
    void 도구_정의에_필수_도구가_모두_있다() {
        assertThat(toolService.definitions())
                .extracting(d -> d.get("name"))
                .contains("list_rooms", "create_room", "get_schema", "replace_schema",
                        "preview_ddl", "import_ddl");
    }

    @Test
    void list_rooms_는_방과_테이블수를_반환한다() {
        JsonNode out = toolService.call("list_rooms", objectMapper.createObjectNode());
        assertThat(out.path("rooms").isArray()).isTrue();
        assertThat(out.path("rooms").toString()).contains("t_mcp_room");
    }

    @Test
    void import_ddl_후_get_schema_로_확인할_수_있다() {
        ObjectNode importArgs = args();
        importArgs.put("ddl", """
                CREATE TABLE `member_base` (`m_no` int NOT NULL, PRIMARY KEY (`m_no`)) COMMENT='회원 정보';
                """);
        importArgs.put("mode", "replace");
        JsonNode summary = toolService.call("import_ddl", importArgs);
        assertThat(summary.path("ok").asBoolean()).isTrue();
        assertThat(summary.path("added").toString()).contains("member_base");

        JsonNode schema = toolService.call("get_schema", args());
        assertThat(schema.path("tables").toString()).contains("member_base");
    }

    @Test
    void replace_schema_로_도메인_재구성이_가능하다() {
        ObjectNode importArgs = args();
        importArgs.put("ddl", """
                CREATE TABLE `member_base` (`m_no` int NOT NULL, PRIMARY KEY (`m_no`)) COMMENT='회원 정보';
                """);
        toolService.call("import_ddl", importArgs);

        // get_schema → 도메인을 LLM 이 재분류했다고 가정하고 문서를 수정해 replace_schema
        JsonNode schema = toolService.call("get_schema", args());
        ObjectNode doc = schema.deepCopy();
        ObjectNode domains = objectMapper.createObjectNode();
        ObjectNode member = objectMapper.createObjectNode();
        member.put("name", "회원(재분류)");
        member.put("color", "#4f8cff");
        domains.set("member", member);
        doc.set("domains", domains);
        // 테이블 도메인 키도 새 키로 변경
        ObjectNode replaceArgs = args();
        var tables = doc.withArray("tables");
        for (JsonNode t : tables) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) t).set(1,
                    objectMapper.getNodeFactory().textNode("member"));
        }
        replaceArgs.set("doc", doc);
        JsonNode out = toolService.call("replace_schema", replaceArgs);
        assertThat(out.path("ok").asBoolean()).isTrue();
        assertThat(schemaService.loadDoc(roomId).domains().get("member").name()).isEqualTo("회원(재분류)");
    }

    @Test
    void roomId_없이_호출하면_안내_메시지로_거부한다() {
        assertThatThrownBy(() -> toolService.call("get_schema", objectMapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roomId");
    }

    @Test
    void 알수없는_도구는_거부한다() {
        assertThatThrownBy(() -> toolService.call("nope", objectMapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 도구");
    }
}
