package com.mindcare.domain.notification.dto;

import com.mindcare.domain.notification.entity.Notification;
import java.time.Instant;
import java.util.UUID;

/** NTF-01 목록 한 줄. payload는 내부용이라 내보내지 않는다. */
public record NotificationResponse(UUID id, String type, String title, String body, String link, Instant readAt,
        Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(), n.getReadAt(),
                n.getCreatedAt());
    }
}
