package com.mindcare.domain.admin.controller;

import com.mindcare.domain.admin.dto.AppConfigResponse;
import com.mindcare.domain.admin.dto.SettingResponse;
import com.mindcare.domain.admin.dto.SettingUpdateRequest;
import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.domain.admin.service.AppSettingService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import com.mindcare.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminSettingController {

    private final AppSettingService appSettingService;
    private final AppConfigService appConfigService;

    @Operation(operationId = "ADM-06", summary = "운영 설정 전체 (price_configs)")
    @GetMapping("/admin/settings")
    public PageResponse<SettingResponse> list(@LoginUser AuthUser admin) {
        return PageResponse.last(appSettingService.list(admin));
    }

    @Operation(operationId = "ADM-07", summary = "운영 설정 변경 (키별 형식 검증, 감사 로그, 즉시 반영)")
    @PutMapping("/admin/settings/{key}")
    public SettingResponse update(@LoginUser AuthUser admin, @PathVariable String key,
            @Valid @RequestBody SettingUpdateRequest request) {
        return appSettingService.update(admin, key, request);
    }

    @Operation(operationId = "PUB-02", summary = "기능 플래그·점검 모드 (비로그인)")
    @GetMapping("/public/app-config")
    public AppConfigResponse appConfig() {
        return appConfigService.current();
    }
}
