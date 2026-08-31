package com.daou.erdstudio.web.mcp;

import com.daou.erdstudio.auth.AppUser;
import com.daou.erdstudio.auth.AppUserRepository;
import com.daou.erdstudio.auth.OidcProperties;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.ForbiddenException;
import com.daou.erdstudio.common.UnauthorizedException;
import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.project.PermissionService;
import com.daou.erdstudio.project.Project;
import com.daou.erdstudio.project.ProjectMember;
import com.daou.erdstudio.project.ProjectMemberRepository;
import com.daou.erdstudio.project.ProjectRepository;
import com.daou.erdstudio.project.ProjectRole;
import com.daou.erdstudio.project.ProjectService;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.service.DdlImportService;
import com.daou.erdstudio.service.OpService;
import com.daou.erdstudio.service.RoomService;
import com.daou.erdstudio.service.SchemaService;
import com.daou.erdstudio.ws.OpBroadcaster;
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

/** SSO 켠 환경(enabled=true)을 시뮬레이션해 MCP 도구의 프로젝트 권한 검사를 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class McpToolAccessTest {

    @Autowired
    RoomService roomService;
    @Autowired
    ErdRoomRepository roomRepository;
    @Autowired
    ErdTableRepository tableRepository;
    @Autowired
    SchemaService schemaService;
    @Autowired
    OpService opService;
    @Autowired
    OpBroadcaster opBroadcaster;
    @Autowired
    DdlImportService ddlImportService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ProjectService projectService;
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    ProjectMemberRepository memberRepository;
    @Autowired
    AppUserRepository appUserRepository;

    private McpToolService enabledTools;
    private Long roomId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        OidcProperties props = new OidcProperties();
        props.setEnabled(true);
        PermissionService enabledPermission =
                new PermissionService(props, projectRepository, memberRepository, appUserRepository);
        enabledTools = new McpToolService(roomService, tableRepository, schemaService, opService,
                opBroadcaster, ddlImportService, objectMapper, enabledPermission, projectService);

        Project project = projectService.create("MCP권한테스트");
        projectId = project.getId();
        roomId = roomRepository.save(new ErdRoom("t_mcp_perm", "tester", null, projectId)).getId();
    }

    private Principal user(String username, boolean superAdmin) {
        AppUser u = appUserRepository.save(new AppUser(username, username, "sub-" + username, superAdmin));
        return new Principal(u.getId(), u.getUsername(), superAdmin);
    }

    private ObjectNode roomArgs() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("roomId", roomId);
        return node;
    }

    @Test
    void 비멤버는_방이_안_보이고_조회도_거부된다() {
        Principal stranger = user("mstranger", false);
        JsonNode out = enabledTools.call("list_rooms", objectMapper.createObjectNode(), stranger);
        assertThat(out.path("rooms")).isEmpty();
        assertThatThrownBy(() -> enabledTools.call("get_schema", roomArgs(), stranger))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void 게스트는_로그인부터_요구받는다() {
        assertThatThrownBy(() -> enabledTools.call("get_schema", roomArgs(), Principal.guest()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 뷰어는_조회만_되고_변경은_거부된다() {
        Principal viewer = user("mviewer", false);
        memberRepository.save(new ProjectMember(projectId, viewer.userId(), ProjectRole.VIEWER));

        JsonNode out = enabledTools.call("list_rooms", objectMapper.createObjectNode(), viewer);
        assertThat(out.path("rooms").toString()).contains("t_mcp_perm");
        enabledTools.call("get_schema", roomArgs(), viewer);   // 통과

        assertThatThrownBy(() -> enabledTools.call("replace_schema", roomArgs(), viewer))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> enabledTools.call("import_ddl", roomArgs(), viewer))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void superAdmin은_전체_접근이다() {
        Principal admin = user("msadmin", true);
        JsonNode out = enabledTools.call("list_rooms", objectMapper.createObjectNode(), admin);
        assertThat(out.path("rooms").toString()).contains("t_mcp_perm");
        enabledTools.call("get_schema", roomArgs(), admin);   // 통과
    }
}
