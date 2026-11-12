package com.mindcare.domain.notification.controller;

import com.mindcare.domain.notification.dto.NotificationPageResponse;
import com.mindcare.domain.notification.service.NotificationService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(operationId = "NTF-01", summary = "내 알림 목록 (최신순, 안 읽은 개수 포함)")
    @GetMapping("/notifications")
    public NotificationPageResponse list(@LoginUser AuthUser user,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly) {
        return notificationService.list(user, cursor, limit, unreadOnly);
    }

    @Operation(operationId = "NTF-02", summary = "알림 읽음 처리")
    @PostMapping("/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@LoginUser AuthUser user, @PathVariable UUID notificationId) {
        notificationService.markRead(user, notificationId);
    }

    @Operation(operationId = "NTF-03", summary = "알림 전체 읽음 처리")
    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@LoginUser AuthUser user) {
        notificationService.markAllRead(user);
    }
}
