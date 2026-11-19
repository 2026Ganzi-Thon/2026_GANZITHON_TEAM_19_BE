package com.mindcare.domain.referral.controller;

import com.mindcare.domain.referral.dto.ExpertReferralItem;
import com.mindcare.domain.referral.dto.ExpertReferralRespondRequest;
import com.mindcare.domain.referral.service.ExpertReferralService;
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
public class ExpertReferralController {
    private final ExpertReferralService service;

    @Operation(operationId = "REF-04", summary = "전문가 연계 요청 목록")
    @GetMapping("/expert/referrals")
    public List<ExpertReferralItem> list(@LoginUser AuthUser user) {
        user.requireRole(UserRole.EXPERT);
        return service.list(user.id());
    }

    @Operation(operationId = "REF-05", summary = "전문가 연계 수락·거절")
    @PostMapping("/expert/referrals/{referralId}/respond")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void respond(@LoginUser AuthUser user, @PathVariable UUID referralId, @Valid @RequestBody ExpertReferralRespondRequest request) {
        user.requireRole(UserRole.EXPERT);
        service.respond(user.id(), referralId, request.action(), request.reason());
    }
}
