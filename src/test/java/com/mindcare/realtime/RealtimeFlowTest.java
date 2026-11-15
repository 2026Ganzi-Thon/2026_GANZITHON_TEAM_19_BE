package com.mindcare.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** TRD 14장: 인증 → 입장 → 전송 → 상대 수신 플로우 통합 테스트. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeFlowTest {

    @LocalServerPort int port;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TokenVerifier tokenVerifier;

    User client;
    User provider;
    UUID sessionId;
    UUID slotId;
    WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        client = saveUser("rt-client", UserRole.CLIENT);
        provider = saveUser("rt-provider", UserRole.TRAINEE);
        given(tokenVerifier.verify("token-client")).willReturn(client.getFirebaseUid());
        given(tokenVerifier.verify("token-provider")).willReturn(provider.getFirebaseUid());
        given(tokenVerifier.verify("bad-token")).willThrow(new AppException(ErrorCode.UNAUTHENTICATED));

        Instant start = Instant.now().minus(Duration.ofMinutes(5));
        slotId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        jdbc.update("insert into availability_slots (id, provider_id, start_at, end_at, status) values (?,?,?,?,'booked')",
                slotId, provider.getId(), Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        jdbc.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, 'confirmed', 15000)
                """, sessionId, client.getId(), provider.getId(), slotId,
                Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from messages where session_id = ?", sessionId);
        jdbc.update("delete from sessions where id = ?", sessionId);
        jdbc.update("delete from availability_slots where id = ?", slotId);
        jdbc.update("delete from users where id in (?, ?)", client.getId(), provider.getId());
    }

    @Test
    void 토큰_없이_CONNECT하면_연결이_거절된다() {
        assertThatThrownBy(() -> connect(null).events()).isInstanceOf(Exception.class);
    }

    @Test
    void 잘못된_토큰이면_연결이_거절된다() {
        assertThatThrownBy(() -> connect("bad-token").events()).isInstanceOf(Exception.class);
    }

    @Test
    void 입장하고_메시지를_보내면_상대가_message_new를_받는다() throws Exception {
        Client providerConn = connect("token-provider");
        Client clientConn = connect("token-client");

        // provider 입장: confirmed → in_progress, 응답은 req_id가 같다
        providerConn.send("/app/session.join", "join-1", Map.of("session_id", sessionId.toString()));
        Map<String, Object> joinReply = providerConn.replies.poll(5, TimeUnit.SECONDS);
        assertThat(joinReply).isNotNull();
        assertThat(joinReply.get("ok")).isEqualTo(true);
        assertThat(joinReply.get("req_id")).isEqualTo("join-1");
        assertThat(joinReply.get("session_status")).isEqualTo("in_progress");

        // client 입장 → provider에게 presence
        clientConn.send("/app/session.join", "join-2", Map.of("session_id", sessionId.toString()));
        assertThat(clientConn.replies.poll(5, TimeUnit.SECONDS)).isNotNull();
        Map<String, Object> presence = providerConn.events.poll(5, TimeUnit.SECONDS);
        assertThat(presence).isNotNull();
        assertThat(presence.get("type")).isEqualTo("presence");

        // client가 메시지 전송 → 양쪽 모두 message:new
        clientConn.send("/app/message.send", "send-1",
                Map.of("session_id", sessionId.toString(), "client_msg_id", "cmid-1", "body", "안녕하세요"));
        Map<String, Object> sendReply = clientConn.replies.poll(5, TimeUnit.SECONDS);
        assertThat(sendReply).isNotNull();
        assertThat(sendReply.get("ok")).isEqualTo(true);

        Map<String, Object> event = providerConn.events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("type")).isEqualTo("message:new");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        assertThat(data.get("body")).isEqualTo("안녕하세요");
        assertThat(data.get("client_msg_id")).isEqualTo("cmid-1");
        assertThat(data.get("session_id")).isEqualTo(sessionId.toString());
    }

    @Test
    void 세션_당사자가_아니면_에러_응답이_온다() throws Exception {
        User stranger = saveUser("rt-stranger", UserRole.CLIENT);
        given(tokenVerifier.verify("token-stranger")).willReturn(stranger.getFirebaseUid());
        try {
            Client conn = connect("token-stranger");
            conn.send("/app/session.join", "join-x", Map.of("session_id", sessionId.toString()));
            Map<String, Object> reply = conn.replies.poll(5, TimeUnit.SECONDS);
            assertThat(reply).isNotNull();
            assertThat(reply.get("ok")).isEqualTo(false);
            assertThat(reply.get("error")).isEqualTo("FORBIDDEN_RESOURCE");
            assertThat(reply.get("req_id")).isEqualTo("join-x");
        } finally {
            jdbc.update("delete from users where id = ?", stranger.getId());
        }
    }

    private User saveUser(String name, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-" + name + "-" + UUID.randomUUID()).role(role)
                .nickname(name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .status(UserStatus.ACTIVE).build());
    }

    private Client connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        Client handler = new Client();
        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/v1/ws", new WebSocketHttpHeaders(), connectHeaders, handler)
                .get(5, TimeUnit.SECONDS);
        session.subscribe("/user/queue/events", handler.subscription(handler.events));
        session.subscribe("/user/queue/replies", handler.subscription(handler.replies));
        handler.session = session;
        Thread.sleep(300); // 구독이 브로커에 등록될 시간
        return handler;
    }

    static class Client extends StompSessionHandlerAdapter {
        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> replies = new LinkedBlockingQueue<>();
        StompSession session;

        Map<String, Object> events() throws InterruptedException {
            return events.poll(3, TimeUnit.SECONDS);
        }

        void send(String destination, String reqId, Map<String, Object> payload) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination(destination);
            headers.add("req-id", reqId);
            session.send(headers, payload);
        }

        org.springframework.messaging.simp.stomp.StompFrameHandler subscription(BlockingQueue<Map<String, Object>> queue) {
            return new org.springframework.messaging.simp.stomp.StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                @SuppressWarnings("unchecked")
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add((Map<String, Object>) payload);
                }
            };
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                byte[] payload, Throwable exception) {
            // 테스트에서는 큐 타임아웃으로 실패를 드러낸다
        }
    }
}
