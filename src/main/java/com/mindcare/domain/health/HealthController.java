package com.mindcare.domain.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HLT-01: 배포 헬스체크용. 비인증. 하나라도 안 되면 503.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private static final String OK = "ok";
    private static final String DOWN = "down";

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    @Operation(operationId = "HLT-01", summary = "헬스체크")
    @SecurityRequirements
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String db = check("db", () -> jdbcTemplate.queryForObject("select 1", Integer.class));
        String redis = check("redis", () -> {
            try (var connection = redisConnectionFactory.getConnection()) {
                connection.ping();
            }
        });
        boolean up = OK.equals(db) && OK.equals(redis);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse(up ? OK : DOWN, db, redis));
    }

    private String check(String name, Runnable probe) {
        try {
            probe.run();
            return OK;
        } catch (RuntimeException e) {
            log.warn("health check failed: {}", name, e);
            return DOWN;
        }
    }

    public record HealthResponse(String status, String db, String redis) {
    }
}
