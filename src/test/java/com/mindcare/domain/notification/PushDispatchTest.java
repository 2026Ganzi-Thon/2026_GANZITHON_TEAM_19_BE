package com.mindcare.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.mindcare.domain.notification.entity.DeviceToken;
import com.mindcare.domain.notification.push.PushSender;
import com.mindcare.domain.notification.repository.DeviceTokenRepository;
import com.mindcare.domain.notification.service.NotificationService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 푸시는 커밋 뒤(AFTER_COMMIT)에 비동기로 나가서, 이 테스트는 실제로 커밋하고 끝나면 직접 지운다.
 */
@SpringBootTest
class PushDispatchTest {

    @Autowired NotificationService notificationService;
    @Autowired UserRepository userRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PushSender pushSender;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : createdUsers) {
            jdbc.update("delete from notifications where user_id = ?", id);
            jdbc.update("delete from device_tokens where user_id = ?", id);
            jdbc.update("delete from users where id = ?", id);
        }
    }

    @Test
    void 알림이_커밋되면_그_사용자_기기로_푸시가_나간다() {
        User user = saveUser("push-ok");
        tx.executeWithoutResult(s -> deviceTokenRepository.save(DeviceToken.of(user.getId(), "tok-push-ok", "web")));
        given(pushSender.send(anyList(), anyString(), anyString(), anyMap())).willReturn(List.of());

        tx.executeWithoutResult(s -> notificationService.notify(user.getId(), "session_booked", "새 예약이 있어요",
                "예약을 확인해 주세요.", "/t/sessions", Map.of()));

        verify(pushSender, timeout(3000)).send(eq(List.of("tok-push-ok")), eq("새 예약이 있어요"), eq("예약을 확인해 주세요."),
                argThat(data -> "/t/sessions".equals(data.get("link")) && "session_booked".equals(data.get("type"))
                        && data.containsKey("notification_id")));
    }

    @Test
    void 롤백되면_푸시가_나가지_않는다() {
        User user = saveUser("push-rollback");
        tx.executeWithoutResult(s -> deviceTokenRepository.save(DeviceToken.of(user.getId(), "tok-rollback", "web")));

        tx.executeWithoutResult(s -> {
            notificationService.notify(user.getId(), "test", "제목", "본문", null, Map.of());
            s.setRollbackOnly();
        });

        verify(pushSender, after(500).never()).send(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    void 죽은_토큰은_지운다() {
        User user = saveUser("push-dead");
        tx.executeWithoutResult(s -> {
            deviceTokenRepository.save(DeviceToken.of(user.getId(), "tok-alive", "web"));
            deviceTokenRepository.save(DeviceToken.of(user.getId(), "tok-dead", "web"));
        });
        given(pushSender.send(anyList(), anyString(), anyString(), anyMap())).willReturn(List.of("tok-dead"));

        tx.executeWithoutResult(s -> notificationService.notify(user.getId(), "test", "제목", "본문", null, Map.of()));

        verify(pushSender, timeout(3000)).send(anyList(), anyString(), anyString(), anyMap());
        waitUntil(() -> deviceTokenRepository.findByToken("tok-dead").isEmpty(), Duration.ofSeconds(3));
        assertThat(deviceTokenRepository.findByToken("tok-dead")).isEmpty();
        assertThat(deviceTokenRepository.findByToken("tok-alive")).isPresent();
    }

    @Test
    void 토큰이_없으면_보내지_않는다() {
        User user = saveUser("push-none");

        tx.executeWithoutResult(s -> notificationService.notify(user.getId(), "test", "제목", "본문", null, Map.of()));

        verify(pushSender, after(500).never()).send(any(), any(), any(), any());
    }

    // 비동기 처리가 끝날 때까지 잠깐씩 기다린다
    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private User saveUser(String name) {
        User user = userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-" + name + "-" + UUID.randomUUID())
                .role(UserRole.CLIENT)
                .nickname("푸시" + name.replace("push-", "").substring(0, Math.min(4, name.length() - 5)))
                .status(UserStatus.ACTIVE)
                .build());
        createdUsers.add(user.getId());
        return user;
    }
}
