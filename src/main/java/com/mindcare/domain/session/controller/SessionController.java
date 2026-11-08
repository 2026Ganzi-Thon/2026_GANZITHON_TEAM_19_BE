package com.mindcare.domain.session.controller;
import com.mindcare.domain.session.dto.MoodCheckRequest;
import com.mindcare.domain.session.service.SessionService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
@RestController @RequiredArgsConstructor
public class SessionController {
    private final SessionService sessionService;
    @Operation(operationId = "SES-05", summary = "상담 전후 마음 온도 기록")
    @PostMapping("/sessions/{sessionId}/mood") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordMood(@LoginUser AuthUser user, @PathVariable UUID sessionId, @Valid @RequestBody MoodCheckRequest request) {
        user.requireRole(UserRole.CLIENT); sessionService.recordMood(user.id(), sessionId, request);
    }
}
