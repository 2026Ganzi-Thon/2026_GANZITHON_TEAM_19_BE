package com.mindcare.domain.user.controller;

import com.mindcare.domain.user.dto.ProviderProfileRequest;
import com.mindcare.domain.user.dto.ProviderProfileResponse;
import com.mindcare.domain.user.dto.ProviderPublicResponse;
import com.mindcare.domain.user.service.ProviderProfileService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProviderProfileController {

    private final ProviderProfileService profileService;

    @Operation(operationId = "USR-05", summary = "상담사 공개 프로필 (내담자용, 실명·자격번호 제외)")
    @GetMapping("/providers/{providerId}")
    public ProviderPublicResponse getPublic(@LoginUser AuthUser user, @PathVariable UUID providerId) {
        return profileService.getPublic(user, providerId);
    }

    @Operation(operationId = "USR-06", summary = "내 상담사 프로필 작성·수정 (심사 중에도 가능)")
    @PutMapping("/me/provider-profile")
    public ProviderProfileResponse upsert(@LoginUser AuthUser user,
            @Valid @RequestBody ProviderProfileRequest request) {
        return profileService.upsert(user, request);
    }
}
