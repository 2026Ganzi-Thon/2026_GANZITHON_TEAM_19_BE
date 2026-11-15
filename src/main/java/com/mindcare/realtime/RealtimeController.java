package com.mindcare.realtime;

import com.mindcare.global.error.AppException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * TRD 14-2: SEND /app/&lt;이벤트&gt;. typing을 뺀 모든 요청은 req-id 헤더를 받고
 * 같은 req_id로 그 연결의 /user/queue/replies 에 응답한다.
 * 페이로드 키는 TRD대로 snake_case라서 Map으로 받는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RealtimeController {

    private static final String REPLIES = "/queue/replies";

    private final RealtimeService realtimeService;

    @MessageMapping("session.join")
    @SendToUser(destinations = REPLIES, broadcast = false)
    public Map<String, Object> join(@Payload Map<String, Object> payload,
            @Header("req-id") String reqId, Principal principal) {
        Map<String, Object> reply = realtimeService.join(
                userId(principal), uuid(payload, "session_id"), uuidOrNull(payload, "after_id"));
        return withReqId(reply, reqId);
    }

    @MessageMapping("session.leave")
    @SendToUser(destinations = REPLIES, broadcast = false)
    public Map<String, Object> leave(@Payload Map<String, Object> payload,
            @Header("req-id") String reqId, Principal principal) {
        return withReqId(realtimeService.leave(userId(principal), uuid(payload, "session_id")), reqId);
    }

    @MessageMapping("message.send")
    @SendToUser(destinations = REPLIES, broadcast = false)
    public Map<String, Object> send(@Payload Map<String, Object> payload,
            @Header("req-id") String reqId, Principal principal) {
        Map<String, Object> reply = realtimeService.sendMessage(userId(principal),
                uuid(payload, "session_id"),
                payload.get("client_msg_id") == null ? null : String.valueOf(payload.get("client_msg_id")),
                payload.get("body") == null ? null : String.valueOf(payload.get("body")));
        return withReqId(reply, reqId);
    }

    @MessageMapping("typing")
    public void typing(@Payload Map<String, Object> payload, Principal principal) {
        realtimeService.typing(userId(principal), uuid(payload, "session_id"),
                Boolean.TRUE.equals(payload.get("is_typing")));
    }

    @MessageMapping("crisis.share")
    @SendToUser(destinations = REPLIES, broadcast = false)
    public Map<String, Object> crisisShare(@Payload Map<String, Object> payload,
            @Header("req-id") String reqId, Principal principal) {
        return withReqId(realtimeService.crisisShare(userId(principal), uuid(payload, "session_id")), reqId);
    }

    /** TRD 14-2: 처리 중 예외는 같은 req_id로 {ok:false, error:"<오류 코드>"}. 오류 코드는 12-3과 같다. */
    @MessageExceptionHandler(AppException.class)
    @SendToUser(destinations = REPLIES, broadcast = false)
    public Map<String, Object> onAppException(AppException e,
            @Header(name = "req-id", required = false) String reqId) {
        return errorReply(e.getErrorCode().name(), reqId);
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = REPLIES, broadcast = false)
    public Map<String, Object> onException(Exception e,
            @Header(name = "req-id", required = false) String reqId) {
        log.error("실시간 요청 처리 실패", e);
        return errorReply("INTERNAL", reqId);
    }

    private UUID userId(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    private UUID uuid(Map<String, Object> payload, String key) {
        UUID value = uuidOrNull(payload, key);
        if (value == null) {
            throw new AppException(com.mindcare.global.error.ErrorCode.VALIDATION_ERROR);
        }
        return value;
    }

    private UUID uuidOrNull(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new AppException(com.mindcare.global.error.ErrorCode.VALIDATION_ERROR);
        }
    }

    private Map<String, Object> withReqId(Map<String, Object> reply, String reqId) {
        Map<String, Object> result = new LinkedHashMap<>(reply);
        result.put("req_id", reqId);
        return result;
    }

    private Map<String, Object> errorReply(String code, String reqId) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("ok", false);
        reply.put("error", code);
        reply.put("req_id", reqId);
        return reply;
    }
}
