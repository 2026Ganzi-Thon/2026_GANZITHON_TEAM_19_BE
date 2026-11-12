package com.mindcare.domain.auth.controller;

import com.mindcare.domain.auth.dto.RegisterRequest;
import com.mindcare.domain.auth.service.RegisterService;
import com.mindcare.domain.user.dto.MeResponse;
import com.mindcare.global.auth.AuthInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final RegisterService registerService;

    // 가입 전이라 @LoginUser(사용자 조회)를 못 쓰고, 인터셉터가 검증한 Firebase uid만 받는다
    @Operation(operationId = "AUTH-01", summary = "가입 (역할·닉네임·필수 동의 4종)")
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MeResponse register(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.FIREBASE_UID_ATTRIBUTE) String firebaseUid,
            @Valid @RequestBody RegisterRequest request) {
        return registerService.register(firebaseUid, request);
    }
}
