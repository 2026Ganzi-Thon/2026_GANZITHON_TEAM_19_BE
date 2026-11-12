package com.mindcare.domain.admin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 점검 모드는 인증보다 먼저 본다 (점검 중에는 토큰이 없어도 503으로 안내). */
@Configuration
@RequiredArgsConstructor
public class MaintenanceWebConfig implements WebMvcConfigurer {

    private final MaintenanceInterceptor maintenanceInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(maintenanceInterceptor)
                .order(Ordered.HIGHEST_PRECEDENCE)
                .addPathPatterns("/**")
                // 경로는 /v1을 뺀 값. 어드민은 점검을 켜고 끌 수 있어야 하고, 공개 API(PUB-02·위기 연락처)는 점검 중에도 보여야 한다
                .excludePathPatterns(
                        "/admin/**", "/public/**", "/health", "/actuator/**", "/error",
                        "/openapi.json", "/openapi.json/**", "/docs", "/docs/**", "/swagger-ui/**");
    }
}
