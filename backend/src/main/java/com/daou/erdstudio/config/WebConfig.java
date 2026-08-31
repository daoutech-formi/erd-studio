package com.daou.erdstudio.config;

import com.daou.erdstudio.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인증 인터셉터를 /api/** 에 등록한다. 게스트를 넣기만 하고 아무 요청도 막지 않는다.
 * WebSocket(/ws)·MCP(/sse, /mcp)·정적 리소스는 대상이 아니다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /api/auth/** 도 대상이다. AuthController.me() 가 AuthContext 를 읽는다.
        registry.addInterceptor(authInterceptor)
                .order(0)
                .addPathPatterns("/api/**");
    }
}
