package com.mindcare.domain.notification.service;

import com.mindcare.domain.notification.dto.NotificationPageResponse;
import com.mindcare.domain.notification.dto.NotificationResponse;
import com.mindcare.domain.notification.entity.Notification;
import com.mindcare.domain.notification.push.NotificationCreatedEvent;
import com.mindcare.domain.notification.repository.NotificationRepository;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.response.CursorCodec;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD 17-3: 다른 도메인은 notify() 한 번 호출로 알림을 보낸다.
 * ① notifications 행 저장(호출한 트랜잭션 안) ② 커밋 후 FCM 푸시(PushDispatcher, 토큰 있을 때).
 * 실시간(STOMP notification:new)은 STOMP 인증이 생기면 여기에 붙인다.
 * 호출한 쪽 트랜잭션에 같이 묶여서, 본 작업이 롤백되면 알림도 안 남는다.
 *
 * 조회(NTF-01~03)는 모든 역할·상태가 쓴다 (정지·심사 중에도 심사 결과·제재 알림을 봐야 함).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final NotificationRepository notificationRepository;
    private final CursorCodec cursorCodec;
    private final ApplicationEventPublisher events;

    /**
     * 알림 저장 + (커밋 후) 그 사용자 기기로 푸시.
     * title·body는 푸시로 잠금 화면에도 보이므로 상담 내용·위험 수준 같은 민감정보를 넣지 않는다 (TRD 17-3).
     */
    @Transactional
    public void notify(UUID userId, String type, String title, String body, String link,
            Map<String, Object> payload) {
        Notification saved = notificationRepository.save(Notification.of(userId, type, title, body, link, payload));
        events.publishEvent(new NotificationCreatedEvent(saved.getId(), userId, type, title, body, link));
    }

    @Transactional
    public void notifyAll(Collection<UUID> userIds, String type, String title, String body, String link,
            Map<String, Object> payload) {
        for (UUID userId : userIds) {
            notify(userId, type, title, body, link, payload);
        }
    }

    /** NTF-01. 최신순, 다음 페이지는 next_cursor로. unread_count는 필터와 상관없이 전체 안 읽은 수. */
    @Transactional(readOnly = true)
    public NotificationPageResponse list(AuthUser user, String cursor, Integer limit, boolean unreadOnly) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "limit", "reason", "range"))));
        }
        UUID before = cursor == null || cursor.isBlank() ? null : parseCursor(cursor);
        // 한 개 더 읽어서 다음 페이지가 있는지 본다
        Limit fetch = Limit.of(size + 1);
        List<Notification> rows;
        if (unreadOnly) {
            rows = before == null
                    ? notificationRepository.findByUserIdAndReadAtIsNullOrderByIdDesc(user.id(), fetch)
                    : notificationRepository.findByUserIdAndReadAtIsNullAndIdLessThanOrderByIdDesc(user.id(), before, fetch);
        } else {
            rows = before == null
                    ? notificationRepository.findByUserIdOrderByIdDesc(user.id(), fetch)
                    : notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(user.id(), before, fetch);
        }
        boolean hasNext = rows.size() > size;
        List<Notification> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext ? cursorCodec.encode(page.getLast().getId().toString()) : null;
        return new NotificationPageResponse(
                page.stream().map(NotificationResponse::from).toList(),
                nextCursor,
                notificationRepository.countByUserIdAndReadAtIsNull(user.id()));
    }

    /** NTF-02. 본인 알림만. 이미 읽었으면 그대로 (멱등). */
    @Transactional
    public void markRead(AuthUser user, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!notification.getUserId().equals(user.id())) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        notification.markRead();
    }

    /** NTF-03. */
    @Transactional
    public void markAllRead(AuthUser user) {
        notificationRepository.markAllRead(user.id(), Instant.now());
    }

    private UUID parseCursor(String cursor) {
        String value = cursorCodec.decode(cursor);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "목록 커서가 올바르지 않습니다", Map.of("field", "cursor"));
        }
    }
}
