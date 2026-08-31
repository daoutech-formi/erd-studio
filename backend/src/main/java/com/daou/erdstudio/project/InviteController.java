package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.AuthContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 초대 링크 API. /api/projects/{slug}/invites* 는 PermissionInterceptor 의
 * "/api/projects/{slug}/** = 해당 프로젝트 ADMIN" 규칙이 지키고,
 * /api/invites/{token} 은 미리보기(게스트 허용)·수락(로그인 필요)이다.
 */
@RestController
public class InviteController {

    public record CreateInviteRequest(ProjectRole role, Integer expiresInDays, Integer maxUses) {
    }

    private final InviteService inviteService;
    private final ProjectRepository projectRepository;

    public InviteController(InviteService inviteService, ProjectRepository projectRepository) {
        this.inviteService = inviteService;
        this.projectRepository = projectRepository;
    }

    @GetMapping("/api/projects/{slug}/invites")
    public List<InviteService.InviteView> list(@PathVariable String slug) {
        return inviteService.list(resolve(slug).getId());
    }

    @PostMapping("/api/projects/{slug}/invites")
    public InviteService.InviteView create(@PathVariable String slug,
                                           @RequestBody CreateInviteRequest req) {
        return inviteService.create(resolve(slug).getId(), req.role(), req.expiresInDays(), req.maxUses(),
                AuthContext.get().userId());
    }

    @DeleteMapping("/api/projects/{slug}/invites/{inviteId}")
    public Map<String, Object> revoke(@PathVariable String slug, @PathVariable Long inviteId) {
        inviteService.revoke(resolve(slug).getId(), inviteId);
        return Map.of("ok", true);
    }

    @GetMapping("/api/invites/{token}")
    public InviteService.PreviewView preview(@PathVariable String token) {
        return inviteService.preview(token, AuthContext.get());
    }

    @PostMapping("/api/invites/{token}/accept")
    public InviteService.AcceptView accept(@PathVariable String token) {
        return inviteService.accept(token, AuthContext.get());
    }

    private Project resolve(String slug) {
        return projectRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 프로젝트입니다."));
    }
}
