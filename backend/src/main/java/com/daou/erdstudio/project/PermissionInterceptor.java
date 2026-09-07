package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.AuthContext;
import com.daou.erdstudio.auth.OidcProperties;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.ForbiddenException;
import com.daou.erdstudio.common.UnauthorizedException;
import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.repository.ErdRoomRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * URL → 대상 프로젝트 → 요구 수준(GET/HEAD=READ, 그 외=WRITE, 관리 API=ADMIN) 권한 검사.
 * SSO 미사용(enabled=false)이면 아무것도 검사하지 않는다(기존 동작 유지).
 * 방 경로는 X-Project-Id 헤더가 아닌 URL 의 방이 속한 프로젝트로 판정한다(남의 방 id 우회 차단).
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String PROJECTS = "/api/projects";
    private static final String ROOMS = "/api/rooms";
    private static final String INVITES = "/api/invites";
    private static final String ADMIN = "/api/admin";

    private final OidcProperties oidcProperties;
    private final PermissionService permissionService;
    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final ErdRoomRepository roomRepository;

    public PermissionInterceptor(OidcProperties oidcProperties, PermissionService permissionService,
                                 ProjectService projectService, ProjectRepository projectRepository,
                                 ErdRoomRepository roomRepository) {
        this.oidcProperties = oidcProperties;
        this.permissionService = permissionService;
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.roomRepository = roomRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!oidcProperties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();
        Principal principal = AuthContext.get();

        if (path.equals(ADMIN) || path.startsWith(ADMIN + "/")) {
            // 전사 현황 등 관리 API — 최고관리자 전용.
            if (!principal.authenticated()) {
                throw new UnauthorizedException("로그인이 필요합니다.");
            }
            if (!principal.superAdmin()) {
                throw new ForbiddenException("최고관리자만 사용할 수 있습니다.");
            }
            return true;
        }
        if (path.equals(PROJECTS)) {
            // 목록은 컨트롤러가 가시성으로 거르고, 생성은 로그인만 요구한다(생성자가 ADMIN 이 된다).
            if (!"GET".equals(method) && !principal.authenticated()) {
                throw new UnauthorizedException("로그인이 필요합니다.");
            }
            return true;
        }
        if (path.startsWith(PROJECTS + "/")) {
            // 삭제·멤버 관리·배정 후보 조회 — 모두 해당 프로젝트 ADMIN 전용.
            String slug = firstSegment(path.substring(PROJECTS.length() + 1));
            Long projectId = projectRepository.findBySlug(slug).map(Project::getId).orElse(null);
            if (projectId == null) {
                return true;   // 컨트롤러가 기존대로 400 — 존재 여부 외 정보 노출 없음
            }
            permissionService.require(principal, projectId, Level.ADMIN);
            return true;
        }
        if (path.startsWith(INVITES + "/")) {
            // 미리보기(GET)는 게스트도 허용(로그인 유도 화면), 수락 등 그 외는 로그인 필요.
            if (!"GET".equals(method) && !principal.authenticated()) {
                throw new UnauthorizedException("로그인이 필요합니다.");
            }
            return true;
        }
        if (path.equals(ROOMS + "/creator-name")) {
            return true;   // 게스트용 표시명 기능 — 프로젝트와 무관
        }
        if (path.equals(ROOMS)) {
            permissionService.require(principal, projectService.currentProjectId(), requiredOf(method));
            return true;
        }
        if (path.startsWith(ROOMS + "/")) {
            Long roomId = parseLong(firstSegment(path.substring(ROOMS.length() + 1)));
            if (roomId == null) {
                return true;
            }
            ErdRoom room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                return true;   // 컨트롤러가 기존대로 400
            }
            Long projectId = room.getProjectId() != null
                    ? room.getProjectId()
                    : projectService.ensureLegacy().getId();
            permissionService.require(principal, projectId, requiredOf(method));
        }
        return true;
    }

    private static Level requiredOf(String method) {
        return "GET".equals(method) || "HEAD".equals(method) ? Level.READ : Level.WRITE;
    }

    private static String firstSegment(String rest) {
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
