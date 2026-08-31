package com.daou.erdstudio.config;

import com.daou.erdstudio.auth.AuthInterceptor;
import com.daou.erdstudio.project.PermissionInterceptor;
import com.daou.erdstudio.project.ProjectInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인증 → 프로젝트 컨텍스트 → 권한 검사 순으로 인터셉터를 /api/** 에 등록한다.
 * 권한 검사는 SSO(erd.oidc.enabled)가 켜졌을 때만 동작한다.
 * WebSocket(/ws)·MCP(/sse, /mcp)·정적 리소스는 대상이 아니다(Phase 5 에서 정합).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final ProjectInterceptor projectInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, ProjectInterceptor projectInterceptor,
                     PermissionInterceptor permissionInterceptor) {
        this.authInterceptor = authInterceptor;
        this.projectInterceptor = projectInterceptor;
        this.permissionInterceptor = permissionInterceptor;
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

        // 로그인·헬스는 권한 검사 대상이 아니다.
        registry.addInterceptor(permissionInterceptor)
                .order(2)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/health");
    }
}
