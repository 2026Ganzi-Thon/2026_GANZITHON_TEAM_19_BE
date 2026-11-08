package com.mindcare.domain.intake.controller;

import com.mindcare.domain.intake.dto.IntakeCreateRequest;
import com.mindcare.domain.intake.dto.IntakeListItemResponse;
import com.mindcare.domain.intake.dto.IntakeResponse;
import com.mindcare.domain.intake.service.IntakeService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class IntakeController {
    private final IntakeService intakeService;

    @Operation(operationId = "INT-01", summary = "인테이크 생성")
    @PostMapping("/intakes")
    public IntakeResponse create(@LoginUser AuthUser user, @Valid @RequestBody IntakeCreateRequest request) {
        user.requireRole(UserRole.CLIENT);
        return intakeService.create(user.id(), request);
    }

    @Operation(operationId = "INT-02", summary = "인테이크 매칭 결과 조회")
    @GetMapping("/intakes/{intakeId}/matches")
    public List<IntakeResponse.Match> matches(@LoginUser AuthUser user, @PathVariable UUID intakeId) {
        user.requireRole(UserRole.CLIENT);
        return intakeService.matches(user.id(), intakeId);
    }

    @Operation(operationId = "INT-03", summary = "내 인테이크 목록 조회")
    @GetMapping("/intakes")
    public List<IntakeListItemResponse> list(@LoginUser AuthUser user) {
        user.requireRole(UserRole.CLIENT);
        return intakeService.list(user.id());
    }
}
