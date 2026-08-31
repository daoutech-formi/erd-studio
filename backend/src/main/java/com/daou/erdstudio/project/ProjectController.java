package com.daou.erdstudio.project;

import com.daou.erdstudio.repository.ErdRoomRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 프로젝트 목록/생성/삭제 API. 가시성·권한 제한은 Phase 3 에서 붙는다(지금은 전원 공개). */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record ProjectInfo(Long id, String slug, String name, long roomCount) {
    }

    public record CreateProjectRequest(String name) {
    }

    private final ProjectService projectService;
    private final ErdRoomRepository roomRepository;

    public ProjectController(ProjectService projectService, ErdRoomRepository roomRepository) {
        this.projectService = projectService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<ProjectInfo> list() {
        return projectService.list().stream()
                .map(p -> new ProjectInfo(p.getId(), p.getSlug(), p.getName(),
                        roomRepository.countByProjectId(p.getId())))
                .toList();
    }

    @PostMapping
    public ProjectInfo create(@RequestBody CreateProjectRequest req) {
        Project p = projectService.create(req.name());
        return new ProjectInfo(p.getId(), p.getSlug(), p.getName(), 0);
    }

    @DeleteMapping("/{slug}")
    public Map<String, Object> delete(@PathVariable String slug) {
        projectService.delete(slug);
        return Map.of("ok", true);
    }
}
