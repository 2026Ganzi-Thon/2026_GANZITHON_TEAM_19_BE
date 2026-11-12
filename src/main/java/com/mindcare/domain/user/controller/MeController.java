package com.mindcare.domain.user.controller;

import com.mindcare.domain.user.dto.MeResponse;
import com.mindcare.domain.user.service.ProviderProfileService;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final UserService userService;
    private final ProviderProfileService profileService;

    // TRD: 전체 역할, 모든 상태 허용 (정지·심사 중 계정도 자기 상태를 봐야 안내 화면을 띄움)
    @Operation(operationId = "USR-01", summary = "내 정보 조회")
    @GetMapping("/me")
    public MeResponse me(@LoginUser AuthUser user) {
        return MeResponse.from(userService.getById(user.id()), profileService.findMine(user.id()).orElse(null));
    }
}
