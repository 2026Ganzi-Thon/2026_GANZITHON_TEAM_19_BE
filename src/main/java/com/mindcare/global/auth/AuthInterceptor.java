package com.mindcare.global.auth;

import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TRD 12-1: 공개 경로(WebConfig에서 제외)를 뺀 모든 API는 Firebase ID 토큰이 필요하다.
 * 여기서는 토큰만 확인해서 uid를 요청에 담고, 사용자 조회는 @LoginUser를 쓰는 컨트롤러에서 한다.
 * (AUTH-01 가입처럼 토큰은 있지만 아직 사용자가 없는 요청도 지나가야 해서)
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    static final String FIREBASE_UID_ATTRIBUTE = "firebaseUid";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenVerifier tokenVerifier;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 컨트롤러가 아닌 요청(없는 경로의 정적 리소스, CORS preflight)은 그쪽에서 응답하게 둔다
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        request.setAttribute(FIREBASE_UID_ATTRIBUTE, tokenVerifier.verify(token));
        return true;
    }
}
