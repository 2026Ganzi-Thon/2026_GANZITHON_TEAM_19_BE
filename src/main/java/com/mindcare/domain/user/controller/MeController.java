package com.mindcare.domain.user.controller;

import com.mindcare.domain.user.dto.MeResponse;
import com.mindcare.domain.user.dto.NicknameUpdateRequest;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.service.ProviderProfileService;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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
        return toResponse(userService.getById(user.id()));
    }

    @Operation(operationId = "USR-02", summary = "닉네임 수정 (가입과 같은 규칙, 중복 409)")
    @PatchMapping("/me")
    public MeResponse updateNickname(@LoginUser AuthUser user, @Valid @RequestBody NicknameUpdateRequest request) {
        return toResponse(userService.changeNickname(user, request.nickname()));
    }

    @Operation(operationId = "USR-07", summary = "윤리 재교육 완료 (수련생, 정지 중에도 가능)")
    @PostMapping("/me/ethics-training/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeEthicsTraining(@LoginUser AuthUser user) {
        userService.completeEthicsTraining(user);
    }

    private MeResponse toResponse(User user) {
        return MeResponse.from(user, profileService.findMine(user.getId()).orElse(null));
    }
}
