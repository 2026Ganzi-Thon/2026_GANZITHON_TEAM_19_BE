package com.mindcare.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * IdempotencyInterceptor가 "처음 온 키"로 표시한 요청의 응답을 Redis에 저장한다.
 * 5xx는 저장하지 않고 표시를 지워서, 같은 키로 다시 시도할 수 있게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(IdempotencyInterceptor.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            Object redisKey = request.getAttribute(IdempotencyInterceptor.REDIS_KEY_ATTRIBUTE);
            if (redisKey != null) {
                if (wrapper.getStatus() >= 500) {
                    redis.delete((String) redisKey);
                } else {
                    String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                    redis.opsForValue().set((String) redisKey, wrapper.getStatus() + "\n" + body, IdempotencyInterceptor.TTL);
                }
            }
            wrapper.copyBodyToResponse();
        }
    }
}
