package com.daou.erdstudio.project;

import com.daou.erdstudio.repository.ErdRoomRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 최고관리자(superAdmin) 전용 전사 조회 API.
 * 접근 제어는 PermissionInterceptor 가 /api/admin/** 전체에 superAdmin 을 요구한다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 프로젝트 하나의 멤버십 현황 — 어떤 프로젝트에 누가 어떤 역할로 속해 있는지. */
    public record ProjectMembershipView(String slug, String name, long roomCount,
                                        List<PermissionService.MemberView> members) {
    }

    private final ProjectRepository projectRepository;
    private final PermissionService permissionService;
    private final ErdRoomRepository roomRepository;

    public AdminController(ProjectRepository projectRepository, PermissionService permissionService,
                           ErdRoomRepository roomRepository) {
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
        this.roomRepository = roomRepository;
    }

    /** 전 프로젝트의 멤버·역할 현황(읽기 전용) — 수정은 기존 프로젝트별 멤버 API 를 사용한다. */
    @GetMapping("/memberships")
    public List<ProjectMembershipView> memberships() {
        return projectRepository.findAllByOrderByIdAsc().stream()
                .map(p -> new ProjectMembershipView(p.getSlug(), p.getName(),
                        roomRepository.countByProjectId(p.getId()),
                        permissionService.members(p.getId())))
                .toList();
    }
}
