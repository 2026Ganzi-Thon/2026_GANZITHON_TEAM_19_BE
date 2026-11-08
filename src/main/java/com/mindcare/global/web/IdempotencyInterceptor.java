package com.mindcare.global.web;

import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @Idempotent API의 중복 요청 처리. 저장은 IdempotencyFilter가 응답이 끝난 뒤에 한다.
 * - 처음 온 키: "처리 중" 표시를 남기고 통과
 * - 같은 키가 처리 중: 409 INVALID_STATE
 * - 같은 키가 끝난 요청: 저장해 둔 첫 응답을 그대로 돌려줌
 * 키는 사용자별로 나눈다 (다른 사람이 같은 키를 써도 섞이지 않게). AuthInterceptor 다음에 실행되어야 한다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Idempotency-Key";
    static final String REDIS_KEY_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".redisKey";
    static final String PENDING = "PENDING";
    static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_KEY_LENGTH = 100;

    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Idempotent.class)) {
            return true;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new AppException(ErrorCode.BAD_REQUEST, Map.of("header", HEADER));
        }
        Object uid = request.getAttribute("firebaseUid");
        String redisKey = "idem:%s:%s:%s:%s".formatted(uid, request.getMethod(), request.getRequestURI(), key);

        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, PENDING, TTL))) {
            request.setAttribute(REDIS_KEY_ATTRIBUTE, redisKey);
            return true;
        }
        String saved = redis.opsForValue().get(redisKey);
        if (saved == null || PENDING.equals(saved)) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("reason", "idempotent_request_in_progress"));
        }
        replay(response, saved);
        return false;
    }

    // 저장 형식: "<status>\n<body>"
    private static void replay(HttpServletResponse response, String saved) throws IOException {
        int newline = saved.indexOf('\n');
        response.setStatus(Integer.parseInt(saved.substring(0, newline)));
        String body = saved.substring(newline + 1);
        if (!body.isEmpty()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(body);
        }
    }
}
