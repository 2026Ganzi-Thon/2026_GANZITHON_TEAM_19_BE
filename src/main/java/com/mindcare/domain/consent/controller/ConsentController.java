package com.mindcare.domain.consent.controller;

import com.mindcare.domain.consent.dto.ConsentGrantRequest;
import com.mindcare.domain.consent.dto.ConsentResponse;
import com.mindcare.domain.consent.dto.ConsentRevokeRequest;
import com.mindcare.domain.consent.service.ConsentService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ConsentController {
    private final ConsentService consentService;

    @Operation(operationId = "CNS-01", summary = "내 동의 이력 조회")
    @GetMapping("/me/consents")
    public List<ConsentResponse> list(@LoginUser AuthUser user) {
        return consentService.list(user.id()).stream().map(ConsentResponse::from).toList();
    }

    @Operation(operationId = "CNS-02", summary = "리포트 또는 전사본 전송 동의")
    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentResponse grant(@LoginUser AuthUser user, @Valid @RequestBody ConsentGrantRequest request) {
        user.requireRole(UserRole.CLIENT);
        return ConsentResponse.from(consentService.grantTransfer(user.id(), request.type(), request.version(),
                request.targetExpertId(), request.reportId(), request.reportVersion(), request.referralId()));
    }

    @Operation(operationId = "CNS-03", summary = "전송 동의 철회")
    @PostMapping("/consents/{consentId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@LoginUser AuthUser user, @PathVariable UUID consentId,
                       @Valid @RequestBody ConsentRevokeRequest request) {
        consentService.revoke(user.id(), consentId, request.reason());
    }
}
