package com.mindcare.domain.notification.push;

import com.mindcare.domain.notification.entity.DeviceToken;
import com.mindcare.domain.notification.repository.DeviceTokenRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TRD 17-3 ③: 알림이 커밋된 뒤 그 사용자 기기로 푸시. 요청 스레드를 막지 않게 비동기로 보낸다.
 * 토큰이 없으면 아무것도 안 한다. 죽은 토큰은 여기서 지운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatcher {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushSender pushSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        List<String> tokens = deviceTokenRepository.findByUserId(event.userId()).stream()
                .map(DeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            return;
        }
        Map<String, String> data = new HashMap<>();
        data.put("notification_id", event.notificationId().toString());
        data.put("type", event.type());
        if (event.link() != null) {
            data.put("link", event.link());
        }
        try {
            List<String> dead = pushSender.send(tokens, event.title(), event.body(), data);
            if (!dead.isEmpty()) {
                deviceTokenRepository.deleteByTokens(dead);
            }
        } catch (RuntimeException e) {
            log.warn("push dispatch failed notification={} error={}", event.notificationId(),
                    e.getClass().getSimpleName());
        }
    }
}
