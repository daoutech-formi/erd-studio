package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.AppUser;
import com.daou.erdstudio.auth.AppUserRepository;
import com.daou.erdstudio.auth.OidcProperties;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.ForbiddenException;
import com.daou.erdstudio.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 유효 권한 계산과 프로젝트 가시성 판정(cortex PermissionService 를 고정 3역할로 단순화).
 * 계산 순서: ① SSO 미사용(enabled=false) → 전원 ADMIN(기존 동작) ② superAdmin → ADMIN
 * ③ 게스트 → NONE ④ 멤버 → 역할 매핑 ⑤ 비멤버 → NONE.
 */
@Service
public class PermissionService {

    private final OidcProperties oidcProperties;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final AppUserRepository appUserRepository;

    public PermissionService(OidcProperties oidcProperties, ProjectRepository projectRepository,
                             ProjectMemberRepository memberRepository, AppUserRepository appUserRepository) {
        this.oidcProperties = oidcProperties;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional(readOnly = true)
    public Level levelFor(Principal principal, Long projectId) {
        if (!oidcProperties.isEnabled()) {
            return Level.ADMIN;
        }
        if (principal != null && principal.superAdmin()) {
            return Level.ADMIN;
        }
        if (principal == null || !principal.authenticated() || projectId == null) {
            return Level.NONE;
        }
        return memberRepository.findByProjectIdAndUserId(projectId, principal.userId())
                .map(m -> m.getRole().level())
                .orElse(Level.NONE);
    }

    /** 유효 권한이 요구 수준에 미달하면 게스트는 401, 로그인 사용자는 403. */
    @Transactional(readOnly = true)
    public void require(Principal principal, Long projectId, Level required) {
        if (levelFor(principal, projectId).satisfies(required)) {
            return;
        }
        if (principal == null || !principal.authenticated()) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        throw new ForbiddenException("이 프로젝트에 대한 권한이 없습니다. 프로젝트 관리자에게 문의하세요.");
    }

    /** 목록에 노출할 프로젝트 — SSO 미사용이면 전부, superAdmin 전부, 게스트 없음, 멤버는 자기 것만. */
    @Transactional(readOnly = true)
    public List<Project> visibleProjects(Principal principal) {
        if (!oidcProperties.isEnabled() || (principal != null && principal.superAdmin())) {
            return projectRepository.findAllByOrderByIdAsc();
        }
        if (principal == null || !principal.authenticated()) {
            return List.of();
        }
        List<Long> ids = memberRepository.findByUserId(principal.userId()).stream()
                .map(ProjectMember::getProjectId)
                .toList();
        return projectRepository.findAllById(ids).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    /** 목록 응답의 myRole 표기용. superAdmin 은 ADMIN 으로, SSO 미사용·비멤버는 null. */
    @Transactional(readOnly = true)
    public ProjectRole roleOf(Principal principal, Long projectId) {
        if (!oidcProperties.isEnabled()) {
            return null;
        }
        if (principal != null && principal.superAdmin()) {
            return ProjectRole.ADMIN;
        }
        if (principal == null || !principal.authenticated() || projectId == null) {
            return null;
        }
        return memberRepository.findByProjectIdAndUserId(projectId, principal.userId())
                .map(ProjectMember::getRole)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(Long projectId) {
        List<ProjectMember> rows = memberRepository.findByProjectId(projectId);
        Map<Long, AppUser> users = appUserRepository
                .findAllById(rows.stream().map(ProjectMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return rows.stream().map(m -> {
            AppUser u = users.get(m.getUserId());
            return new MemberView(m.getUserId(),
                    u != null ? u.getUsername() : null,
                    u != null ? u.getDisplayName() : null,
                    m.getRole());
        }).toList();
    }

    /** 멤버 목록을 통째로 교체한다. 스스로 잠기지 않도록 새 목록에 ADMIN 이 1명 이상이어야 한다. */
    @Transactional
    public void replaceMembers(Long projectId, List<MemberInput> members) {
        Map<Long, ProjectRole> merged = new LinkedHashMap<>();
        for (MemberInput in : members == null ? List.<MemberInput>of() : members) {
            if (in.userId() == null || in.role() == null) {
                throw new IllegalArgumentException("userId 와 role 은 필수입니다.");
            }
            merged.put(in.userId(), in.role());
        }
        if (merged.values().stream().noneMatch(r -> r == ProjectRole.ADMIN)) {
            throw new IllegalArgumentException("프로젝트에는 관리자가 1명 이상 있어야 합니다.");
        }
        memberRepository.deleteByProjectId(projectId);
        memberRepository.flush();   // (project_id, user_id) 유니크 충돌 방지
        memberRepository.saveAll(merged.entrySet().stream()
                .map(e -> new ProjectMember(projectId, e.getKey(), e.getValue()))
                .toList());
    }

    /** 멤버로 지정할 수 있는 계정 — superAdmin 은 어차피 전권이라 제외한다. */
    @Transactional(readOnly = true)
    public List<AssignableUserView> assignableUsers() {
        return appUserRepository.findAll().stream()
                .filter(u -> !u.isSuperAdmin())
                .map(u -> new AssignableUserView(u.getId(), u.getUsername(), u.getDisplayName()))
                .toList();
    }

    public record MemberView(Long userId, String username, String displayName, ProjectRole role) {
    }

    public record MemberInput(Long userId, ProjectRole role) {
    }

    public record AssignableUserView(Long id, String username, String displayName) {
    }
}
