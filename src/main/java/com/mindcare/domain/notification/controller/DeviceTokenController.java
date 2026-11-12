package com.mindcare.domain.notification.controller;

import com.mindcare.domain.notification.dto.DeviceTokenRequest;
import com.mindcare.domain.notification.service.DeviceTokenService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @Operation(operationId = "USR-04", summary = "FCM 기기 토큰 등록 (upsert)")
    @PostMapping("/me/device-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@LoginUser AuthUser user, @Valid @RequestBody DeviceTokenRequest request) {
        deviceTokenService.register(user, request.token(), request.platformOrWeb());
    }

    // FCM 토큰에는 ':'가 들어가지만 경로에 그대로 넣어도 된다 (프론트는 encodeURIComponent로 보냄)
    @Operation(operationId = "USR-04", summary = "FCM 기기 토큰 해제 (로그아웃 시)")
    @DeleteMapping("/me/device-tokens/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@LoginUser AuthUser user, @PathVariable String token) {
        deviceTokenService.unregister(user, token);
    }
}
