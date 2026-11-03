package com.mindcare.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * TRD 6-5: Firebase Hosting 도메인과 로컬 프론트만 허용. 값은 CORS_ORIGINS 환경변수(콤마 구분).
 * 인증은 Authorization 헤더(Bearer)라서 쿠키를 안 쓰므로 allowCredentials는 끈다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Request-ID", "Idempotency-Key")
                .exposedHeaders("X-Request-ID", "Retry-After")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
