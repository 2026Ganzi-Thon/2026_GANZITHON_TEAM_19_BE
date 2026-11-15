package com.mindcare.realtime;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.TokenVerifier;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * TRD 14-1: CONNECT 프레임의 Authorization: Bearer &lt;ID 토큰&gt;을 HTTP와 같은 방식으로 검증하고
 * principal(users.id)을 연결에 심는다. 실패하면 예외 → ERROR 프레임 후 연결 종료.
 * 구독은 자기 큐 2개(/user/queue/events, /user/queue/replies)만 허용하고 나머지는 버린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements org.springframework.messaging.support.ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> ALLOWED_SUBSCRIPTIONS =
            Set.of("/user/queue/events", "/user/queue/replies");

    private final TokenVerifier tokenVerifier;
    private final UserService userService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (destination == null || !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
                    log.warn("허용되지 않은 구독을 버립니다: {}", destination);
                    return null;
                }
            }
            case SEND -> {
                if (accessor.getUser() == null) {
                    throw new AppException(ErrorCode.UNAUTHENTICATED);
                }
            }
            default -> { }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        User user = userService.getByFirebaseUid(tokenVerifier.verify(token));
        accessor.setUser(new StompPrincipal(user.getId().toString()));
    }
}
