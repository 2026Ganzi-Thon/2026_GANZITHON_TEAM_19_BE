package com.mindcare.realtime;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * TRD 14-3: 서버 → 클라이언트 이벤트는 전부 /user/queue/events 로 같은 봉투에 담아 보낸다.
 * {"type": "...", "data": {...}, "ts": "..."}
 * (TRD 14-4의 Redis pub/sub 릴레이는 api 단일 인스턴스 운영이라 생략하고 로컬 브로커로 직접 보낸다)
 */
@Component
@RequiredArgsConstructor
public class RealtimePublisher {

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public void publish(Collection<UUID> userIds, String type, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        envelope.put("ts", clock.instant().toString());
        for (UUID userId : userIds) {
            messaging.convertAndSendToUser(userId.toString(), "/queue/events", envelope);
        }
    }

    public void publish(UUID userId, String type, Object data) {
        publish(List.of(userId), type, data);
    }
}
