package com.mindcare.domain.admin.controller;

import com.mindcare.domain.admin.dto.CredentialDecisionRequest;
import com.mindcare.domain.admin.dto.CredentialDecisionResponse;
import com.mindcare.domain.admin.dto.CredentialReviewDetail;
import com.mindcare.domain.admin.dto.CredentialReviewItem;
import com.mindcare.domain.admin.service.AdminCredentialService;
import com.mindcare.domain.credential.entity.CredentialKind;
import com.mindcare.domain.credential.entity.VerifyResult;
import com.mindcare.domain.user.entity.UserRole;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminCredentialController {

    private final AdminCredentialService adminCredentialService;

    @Operation(operationId = "ADM-01", summary = "자격 심사 대기 목록")
    @GetMapping("/admin/credentials")
    public PageResponse<CredentialReviewItem> list(@LoginUser AuthUser admin,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String kind,
            @RequestParam(name = "verify_result", required = false) String verifyResult) {
        return adminCredentialService.listPending(admin, parse(UserRole.class, role), parse(CredentialKind.class, kind),
                parse(VerifyResult.class, verifyResult));
    }

    @Operation(operationId = "ADM-02", summary = "자격 심사 상세 (실명·자격번호·서류 URL, 열람 기록)")
    @GetMapping("/admin/credentials/{userId}")
    public CredentialReviewDetail detail(@LoginUser AuthUser admin, @PathVariable UUID userId) {
        return adminCredentialService.getDetail(admin, userId);
    }

    @Operation(operationId = "ADM-03", summary = "자격 승인·반려 (반려는 사유 필수)")
    @PostMapping("/admin/credentials/{userId}/decision")
    public CredentialDecisionResponse decide(@LoginUser AuthUser admin, @PathVariable UUID userId,
            @Valid @RequestBody CredentialDecisionRequest request) {
        return adminCredentialService.decide(admin, userId, request);
    }

    // 쿼리 파라미터는 소문자 값(trainee, national, match)으로 받는다. 모르는 값은 422
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
