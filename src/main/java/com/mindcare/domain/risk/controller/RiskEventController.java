package com.mindcare.domain.risk.controller;

import com.mindcare.domain.risk.dto.RiskEventResponse;
import com.mindcare.domain.risk.dto.RiskResolutionRequest;
import com.mindcare.domain.risk.service.RiskEventService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class RiskEventController {
    private final RiskEventService riskEventService;
    @GetMapping("/risk-events")
    public List<RiskEventResponse> list(@LoginUser AuthUser user) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT);
        return riskEventService.listForProvider(user.id()).stream().map(RiskEventResponse::from).toList();
    }
    @PostMapping("/risk-events/{eventId}/acknowledge") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(@LoginUser AuthUser user, @PathVariable UUID eventId) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT); riskEventService.acknowledge(user.id(), eventId);
    }
    @PostMapping("/risk-events/{eventId}/resolve") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@LoginUser AuthUser user, @PathVariable UUID eventId, @Valid @RequestBody RiskResolutionRequest request) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT); riskEventService.resolve(user.id(), eventId, request.note());
    }
}
