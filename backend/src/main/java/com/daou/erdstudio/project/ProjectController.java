package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.AuthContext;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.repository.ErdRoomRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 프로젝트 목록/생성/삭제 + 멤버 관리 API.
 * 목록은 가시성(내가 멤버인 것만)으로 걸러지고, 삭제·멤버 관리는 PermissionInterceptor 가
 * 해당 프로젝트 ADMIN 을 요구한다. SSO 미사용이면 기존처럼 전원 전권이다.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    /** myRole: 내 역할(ADMIN/EDITOR/VIEWER). superAdmin 은 ADMIN, SSO 미사용·비멤버는 null. */
    public record ProjectInfo(Long id, String slug, String name, long roomCount, String myRole) {
    }

    public record CreateProjectRequest(String name) {
    }

    public record ReplaceMembersRequest(List<PermissionService.MemberInput> members) {
    }

    private final ProjectService projectService;
    private final PermissionService permissionService;
    private final ProjectRepository projectRepository;
    private final ErdRoomRepository roomRepository;

    public ProjectController(ProjectService projectService, PermissionService permissionService,
                             ProjectRepository projectRepository, ErdRoomRepository roomRepository) {
        this.projectService = projectService;
        this.permissionService = permissionService;
        this.projectRepository = projectRepository;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<ProjectInfo> list() {
        Principal principal = AuthContext.get();
        return permissionService.visibleProjects(principal).stream()
                .map(p -> toInfo(p, principal))
                .toList();
    }

    @PostMapping
    public ProjectInfo create(@RequestBody CreateProjectRequest req) {
        Principal principal = AuthContext.get();
        Project p = projectService.create(req.name(), principal.userId());
        return toInfo(p, principal);
    }

    @DeleteMapping("/{slug}")
    public Map<String, Object> delete(@PathVariable String slug) {
        projectService.delete(slug);
        return Map.of("ok", true);
    }

    @GetMapping("/{slug}/members")
    public List<PermissionService.MemberView> members(@PathVariable String slug) {
        return permissionService.members(resolve(slug).getId());
    }

    @PutMapping("/{slug}/members")
    public Map<String, Object> replaceMembers(@PathVariable String slug,
                                              @RequestBody ReplaceMembersRequest req) {
        permissionService.replaceMembers(resolve(slug).getId(), req.members());
        return Map.of("ok", true);
    }

    @GetMapping("/{slug}/assignable-users")
    public List<PermissionService.AssignableUserView> assignableUsers(@PathVariable String slug) {
        resolve(slug);   // 미지 slug 는 400
        return permissionService.assignableUsers();
    }

    private Project resolve(String slug) {
        return projectRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 프로젝트입니다."));
    }

    private ProjectInfo toInfo(Project p, Principal principal) {
        ProjectRole role = permissionService.roleOf(principal, p.getId());
        return new ProjectInfo(p.getId(), p.getSlug(), p.getName(),
                roomRepository.countByProjectId(p.getId()),
                role == null ? null : role.name());
    }
}
