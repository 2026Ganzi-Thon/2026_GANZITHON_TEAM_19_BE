package com.mindcare.realtime;

import com.mindcare.domain.admin.dto.CrisisContact;
import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.domain.message.dto.MessageResponse;
import com.mindcare.domain.message.dto.MessageSendRequest;
import com.mindcare.domain.message.service.MessageService;
import com.mindcare.domain.session.dto.SessionResponse;
import com.mindcare.domain.session.entity.SessionStatus;
import com.mindcare.domain.session.service.SessionService;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * TRD 14-2 클라이언트 → 서버 이벤트 처리. 권한·당사자 확인은 각 도메인 service가 한다.
 * 응답(reply) 맵과 이벤트 data 맵의 키는 TRD대로 snake_case로 직접 적는다.
 */
@Service
@RequiredArgsConstructor
public class RealtimeService {

    /** TRD 14-1: 입장 가능 시각 = 시작 10분 전 ~ 종료 5분 후 */
    private static final Duration JOIN_BEFORE_START = Duration.ofMinutes(10);
    private static final Duration JOIN_AFTER_END = Duration.ofMinutes(5);
    private static final Set<String> ALERT_LEVELS = Set.of("high", "critical");
    private static final int MAX_BODY_LENGTH = 4000;
    private static final int MAX_CLIENT_MSG_ID_LENGTH = 100;

    private final SessionService sessionService;
    private final MessageService messageService;
    private final AppConfigService appConfigService;
    private final RealtimePublisher publisher;
    private final Clock clock;

    public Map<String, Object> join(UUID actorId, UUID sessionId, UUID afterId) {
        SessionResponse session = sessionService.detail(actorId, sessionId);
        requireJoinWindow(session);
        boolean isProvider = actorId.equals(session.providerId());
        if (isProvider && session.status() == SessionStatus.CONFIRMED) {
            sessionService.markInProgress(actorId, sessionId);
            session = sessionService.detail(actorId, sessionId);
        }
        if (afterId != null) {
            List<Map<String, Object>> messages = messageService.list(actorId, sessionId, afterId).stream()
                    .map(m -> messageData(m, null))
                    .toList();
            publisher.publish(actorId, "message:backfill",
                    Map.of("session_id", sessionId, "messages", messages));
        }
        publisher.publish(peerOf(session, actorId), "presence",
                presenceData(sessionId, isProvider, true));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("ok", true);
        reply.put("session_status", statusValue(session.status()));
        return reply;
    }

    public Map<String, Object> leave(UUID actorId, UUID sessionId) {
        SessionResponse session = sessionService.detail(actorId, sessionId);
        boolean isProvider = actorId.equals(session.providerId());
        publisher.publish(peerOf(session, actorId), "presence",
                presenceData(sessionId, isProvider, false));
        return Map.of("ok", true);
    }

    public Map<String, Object> sendMessage(UUID actorId, UUID sessionId, String clientMsgId, String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH
                || clientMsgId == null || clientMsgId.isBlank() || clientMsgId.length() > MAX_CLIENT_MSG_ID_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        MessageResponse saved = messageService.send(actorId, sessionId, new MessageSendRequest(body, clientMsgId));
        SessionResponse session = sessionService.detail(actorId, sessionId);
        List<UUID> participants = List.of(session.clientId(), session.providerId());
        publisher.publish(participants, "message:new", messageData(saved, clientMsgId));
        if (saved.riskLevel() != null && ALERT_LEVELS.contains(saved.riskLevel())) {
            publisher.publish(participants, "risk:alert",
                    Map.of("session_id", sessionId, "level", saved.riskLevel()));
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("ok", true);
        reply.put("message", Map.of("id", saved.id(), "created_at", saved.createdAt().toString()));
        return reply;
    }

    public void typing(UUID actorId, UUID sessionId, boolean isTyping) {
        SessionResponse session = sessionService.detail(actorId, sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", sessionId);
        data.put("is_typing", isTyping);
        publisher.publish(peerOf(session, actorId), "typing", data);
    }

    public Map<String, Object> crisisShare(UUID actorId, UUID sessionId) {
        SessionResponse session = sessionService.detail(actorId, sessionId);
        if (!actorId.equals(session.providerId())) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        MessageResponse saved = messageService.sendSystem(actorId, sessionId, "crisis_resources", crisisBody());
        publisher.publish(List.of(session.clientId(), session.providerId()),
                "message:new", messageData(saved, null, "crisis_resources"));
        return Map.of("ok", true);
    }

    private void requireJoinWindow(SessionResponse session) {
        if (session.status() == SessionStatus.CANCELLED || session.status() == SessionStatus.PENDING_PAYMENT) {
            throw new AppException(ErrorCode.INVALID_STATE);
        }
        Instant now = clock.instant();
        if (now.isBefore(session.scheduledStart().minus(JOIN_BEFORE_START))
                || now.isAfter(session.scheduledEnd().plus(JOIN_AFTER_END))) {
            throw new AppException(ErrorCode.INVALID_STATE);
        }
    }

    private UUID peerOf(SessionResponse session, UUID actorId) {
        return actorId.equals(session.clientId()) ? session.providerId() : session.clientId();
    }

    private Map<String, Object> presenceData(UUID sessionId, boolean isProvider, boolean online) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", sessionId);
        data.put("user_role", isProvider ? "provider" : "client");
        data.put("online", online);
        return data;
    }

    private Map<String, Object> messageData(MessageResponse message, String clientMsgId) {
        return messageData(message, clientMsgId, "text");
    }

    private Map<String, Object> messageData(MessageResponse message, String clientMsgId, String kind) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", message.id());
        data.put("session_id", message.sessionId());
        data.put("sender_id", message.senderId());
        data.put("kind", kind);
        data.put("body", message.body());
        data.put("risk_level", message.riskLevel());
        data.put("created_at", message.createdAt() == null ? null : message.createdAt().toString());
        data.put("client_msg_id", clientMsgId);
        return data;
    }

    private String crisisBody() {
        List<CrisisContact> contacts = appConfigService.crisisContacts();
        if (contacts.isEmpty()) {
            return "지금 많이 힘드시면 자살예방 상담전화 109(24시간)로 연락해 주세요.";
        }
        return contacts.stream()
                .map(c -> c.name() + " " + c.number() + (c.description() == null ? "" : " — " + c.description()))
                .collect(Collectors.joining("\n"));
    }

    private String statusValue(SessionStatus status) {
        return status.name().toLowerCase();
    }
}
