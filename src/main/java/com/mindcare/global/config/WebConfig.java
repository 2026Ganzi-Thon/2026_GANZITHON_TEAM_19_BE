package com.mindcare.global.config;

import com.mindcare.global.auth.AuthInterceptor;
import com.mindcare.global.auth.LoginUserArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final LoginUserArgumentResolver loginUserArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                // TRD 12-1 비인증 경로 + API 문서. 경로는 /v1을 뺀 값
                .excludePathPatterns(
                        "/public/**", "/health", "/certificates/verify/**",
                        "/openapi.json", "/openapi.json/**", "/docs", "/docs/**", "/swagger-ui/**",
                        "/actuator/**", "/error");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginUserArgumentResolver);
    }
}
