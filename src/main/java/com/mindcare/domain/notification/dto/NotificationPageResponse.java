package com.mindcare.domain.notification.dto;

import java.util.List;

/** NTF-01 응답. TRD: PageResponse(items, next_cursor)에 unread_count가 더 붙는다. */
public record NotificationPageResponse(List<NotificationResponse> items, String nextCursor, long unreadCount) {
}
