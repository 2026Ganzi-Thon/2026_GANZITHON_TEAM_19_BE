package com.mindcare.domain.notification.service;

import com.mindcare.domain.notification.entity.Notification;
import com.mindcare.domain.notification.repository.NotificationRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD 부록 B-6: 다른 도메인은 이 서비스 한 번 호출로 알림을 보낸다.
 * 지금은 인앱 알림(notifications 행)만 저장한다. 실시간(STOMP)·푸시(FCM)는 NTF 작업 때 여기에 붙인다.
 * 호출한 쪽 트랜잭션에 같이 묶여서, 본 작업이 롤백되면 알림도 안 남는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public void notify(UUID userId, String type, String title, String body, String link,
            Map<String, Object> payload) {
        notificationRepository.save(Notification.of(userId, type, title, body, link, payload));
    }

    @Transactional
    public void notifyAll(Collection<UUID> userIds, String type, String title, String body, String link,
            Map<String, Object> payload) {
        for (UUID userId : userIds) {
            notify(userId, type, title, body, link, payload);
        }
    }
}
