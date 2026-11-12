package com.mindcare.domain.admin.controller;

import com.mindcare.domain.admin.dto.AdminReasonRequest;
import com.mindcare.domain.admin.dto.AdminUserStatusRequest;
import com.mindcare.domain.admin.service.AdminUserService;
import com.mindcare.domain.user.dto.AdminUserRow;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(operationId = "ADM-04", summary = "회원 검색 (닉네임·활동명, 이메일·실명 미포함)")
    @GetMapping("/admin/users")
    public PageResponse<AdminUserRow> search(@LoginUser AuthUser admin,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return adminUserService.search(admin, q, parse(UserRole.class, role), parse(UserStatus.class, status),
                cursor, limit);
    }

    @Operation(operationId = "ADM-05", summary = "회원 상태 변경 (사유 필수, active가 아니면 강제 로그아웃)")
    @PatchMapping("/admin/users/{userId}")
    public AdminUserRow changeStatus(@LoginUser AuthUser admin, @PathVariable UUID userId,
            @Valid @RequestBody AdminUserStatusRequest request) {
        return adminUserService.changeStatus(admin, userId, request);
    }

    @Operation(operationId = "ADM-05", summary = "강제 로그아웃 (상태는 그대로)")
    @PostMapping("/admin/users/{userId}/revoke-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeTokens(@LoginUser AuthUser admin, @PathVariable UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminUserService.revokeTokens(admin, userId, request.reason());
    }

    // 쿼리 파라미터는 소문자 값(client, pending_review)으로 받는다. 모르는 값은 422
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        throw new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", "query", "reason", "invalid_value"))));
    }
}
