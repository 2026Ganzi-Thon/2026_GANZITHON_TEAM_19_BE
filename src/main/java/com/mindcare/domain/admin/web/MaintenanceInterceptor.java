package com.mindcare.domain.admin.web;

import com.mindcare.domain.admin.dto.AppConfigResponse.Maintenance;
import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TRD 7-8 점검 모드: 켜지면 어드민·공개 API를 뺀 모든 API가 503 MAINTENANCE.
 * 빠지는 경로는 MaintenanceWebConfig에 있고, 어드민 화면 로그인에 필요한 GET /me만 여기서 따로 통과시킨다.
 */
@Component
@RequiredArgsConstructor
public class MaintenanceInterceptor implements HandlerInterceptor {

    private final AppConfigService appConfigService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Maintenance maintenance = appConfigService.current().maintenance();
        if (!maintenance.on() || isMeLookup(request)) {
            return true;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("until", maintenance.until());
        String message = maintenance.message() == null || maintenance.message().isBlank()
                ? ErrorCode.MAINTENANCE.getMessage() : maintenance.message();
        throw new AppException(ErrorCode.MAINTENANCE, message, details);
    }

    private static boolean isMeLookup(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && "/me".equals(pathWithinApp(request));
    }

    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
