package com.mindcare.domain.session.controller;
import com.mindcare.domain.session.dto.MoodCheckRequest;
import com.mindcare.domain.session.dto.SessionCancelRequest;
import com.mindcare.domain.session.dto.SessionEndRequest;
import com.mindcare.domain.session.dto.SessionNoteRequest;
import com.mindcare.domain.session.dto.SessionReviewRequest;
import com.mindcare.domain.session.dto.SessionResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.List;
@RestController @RequiredArgsConstructor
public class SessionController {
    private final SessionService sessionService;
    @Operation(operationId = "SES-05", summary = "상담 전후 마음 온도 기록")
    @PostMapping("/sessions/{sessionId}/mood") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordMood(@LoginUser AuthUser user, @PathVariable UUID sessionId, @Valid @RequestBody MoodCheckRequest request) {
        user.requireRole(UserRole.CLIENT); sessionService.recordMood(user.id(), sessionId, request);
    }

    @Operation(operationId = "SES-04", summary = "세션 취소")
    @PostMapping("/sessions/{sessionId}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@LoginUser AuthUser user, @PathVariable UUID sessionId, @Valid @RequestBody SessionCancelRequest request) {
        user.requireRole(UserRole.CLIENT, UserRole.TRAINEE, UserRole.EXPERT);
        sessionService.cancel(user.id(), sessionId, request.reason());
    }

    @Operation(operationId = "SES-06", summary = "세션 종료")
    @PostMapping("/sessions/{sessionId}/end") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@LoginUser AuthUser user, @PathVariable UUID sessionId, @Valid @RequestBody SessionEndRequest request) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT); sessionService.end(user.id(), sessionId);
    }
    @Operation(operationId = "SES-02", summary = "내 세션 목록") @GetMapping("/sessions")
    public List<SessionResponse> list(@LoginUser AuthUser user) { return sessionService.list(user.id()); }
    @Operation(operationId = "SES-03", summary = "세션 상세") @GetMapping("/sessions/{sessionId}")
    public SessionResponse detail(@LoginUser AuthUser user, @PathVariable UUID sessionId) { return sessionService.detail(user.id(), sessionId); }
    @Operation(operationId = "SES-07", summary = "세션 후기") @PostMapping("/sessions/{sessionId}/review") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@LoginUser AuthUser user, @PathVariable UUID sessionId, @Valid @RequestBody SessionReviewRequest request) { user.requireRole(UserRole.CLIENT); sessionService.review(user.id(), sessionId, request); }
    @Operation(operationId = "SES-08", summary = "상담 메모") @PutMapping("/sessions/{sessionId}/note") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void note(@LoginUser AuthUser user, @PathVariable UUID sessionId, @Valid @RequestBody SessionNoteRequest request) { user.requireRole(UserRole.TRAINEE, UserRole.EXPERT); sessionService.note(user.id(), sessionId, request.note()); }
}
