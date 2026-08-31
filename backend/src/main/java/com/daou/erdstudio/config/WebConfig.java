package com.daou.erdstudio.config;

import com.daou.erdstudio.auth.AuthInterceptor;
import com.daou.erdstudio.project.ProjectInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인증 → 프로젝트 컨텍스트 순으로 인터셉터를 /api/** 에 등록한다.
 * 둘 다 컨텍스트를 채우기만 하고 아무 요청도 막지 않는다(권한 검사는 Phase 3).
 * WebSocket(/ws)·MCP(/sse, /mcp)·정적 리소스는 대상이 아니다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final ProjectInterceptor projectInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, ProjectInterceptor projectInterceptor) {
        this.authInterceptor = authInterceptor;
        this.projectInterceptor = projectInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /api/auth/** 도 대상이다. AuthController.me() 가 AuthContext 를 읽는다.
        registry.addInterceptor(authInterceptor)
                .order(0)
                .addPathPatterns("/api/**");

        // 인증 API 는 프로젝트 컨텍스트가 필요 없다(스테일 slug 로 로그인 상태 조회가 흔들리지 않게 제외).
        registry.addInterceptor(projectInterceptor)
                .order(1)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/health");
    }
}
