package com.daou.erdstudio.project;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * X-Project-Id 헤더(프로젝트 slug)를 읽어 요청 스레드의 ProjectContext 를 설정/해제한다.
 * 헤더가 없거나 미지의 slug(삭제된 프로젝트를 기억하는 브라우저 등)면 컨텍스트를 비워 두고
 * 서비스가 legacy 프로젝트로 폴백한다. Phase 3(권한)에서 미지 slug 는 엄격하게 거절로 바꾼다.
 */
@Component
public class ProjectInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Project-Id";

    private final ProjectRepository projectRepository;

    public ProjectInterceptor(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String slug = request.getHeader(HEADER);
        if (slug != null && !slug.isBlank()) {
            projectRepository.findBySlug(slug.trim())
                    .ifPresent(project -> ProjectContext.set(project.getId()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        ProjectContext.clear();
    }
}
