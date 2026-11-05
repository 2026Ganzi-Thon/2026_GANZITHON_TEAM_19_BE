package com.mindcare.global.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TRD 12장: OpenAPI(/v1/openapi.json)가 프론트와의 계약 기준. 문서는 /v1/docs.
 * 각 엔드포인트의 operationId는 TRD API ID(예: USR-01)로 단다: @Operation(operationId = "USR-01")
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "firebase";

    // springdoc은 앱의 Jackson 설정을 안 따라가서, 실제 응답과 같은 snake_case로 문서를 만들도록 따로 지정
    @Bean
    public ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json.mapper().copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("MindCare API").version("v1")
                        .description("2026 간지톤 19팀 MindCare 백엔드. 인증: Authorization: Bearer <Firebase ID 토큰>"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("Firebase ID token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
