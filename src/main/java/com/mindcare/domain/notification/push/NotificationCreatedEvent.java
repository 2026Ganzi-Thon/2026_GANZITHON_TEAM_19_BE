package com.mindcare.domain.notification.push;

import java.util.UUID;

/** 알림 행이 저장됐다는 이벤트. 커밋된 뒤에만 푸시가 나간다 (롤백되면 안 보냄). */
public record NotificationCreatedEvent(UUID notificationId, UUID userId, String type, String title, String body,
        String link) {
}
