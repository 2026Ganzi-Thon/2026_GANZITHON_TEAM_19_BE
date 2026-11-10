package com.mindcare.domain.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.entity.SlotStatus;
import com.mindcare.domain.slot.repository.SlotRepository;
import com.mindcare.domain.slot.service.SlotHold;
import com.mindcare.domain.slot.service.SlotService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 예약용 hold / book / release. 동시성 테스트는 실제 커밋이 필요해서 @Transactional 없이 돌리고 끝나면 지운다.
 */
@SpringBootTest
class SlotServiceTest {

    private static final Instant START = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);

    @Autowired SlotService slotService;
    @Autowired SlotRepository slotRepository;
    @Autowired UserRepository userRepository;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdUsers.forEach(userId -> {
            slotRepository.findAll().stream().filter(s -> s.getProviderId().equals(userId)).forEach(slotRepository::delete);
            userRepository.deleteById(userId);
        });
    }

    @Test
    void 같은_슬롯을_10명이_동시에_잡아도_1명만_성공한다() throws Exception {
        AvailabilitySlot slot = saveSlot(saveUser(UserRole.TRAINEE));
        int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    slotService.hold(slot.getId());
                    return null;
                } catch (AppException e) {
                    return e.getErrorCode();
                }
            }));
        }
        ready.await();
        go.countDown();

        int succeeded = 0;
        int taken = 0;
        for (Future<ErrorCode> result : results) {
            ErrorCode code = result.get();
            if (code == null) succeeded++;
            else if (code == ErrorCode.SLOT_TAKEN) taken++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(taken).isEqualTo(9);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    void 홀드하면_10분_만료_시각과_트랙_가격을_돌려준다() {
        User expert = saveUser(UserRole.EXPERT);
        AvailabilitySlot slot = saveSlot(expert);

        SlotHold hold = slotService.hold(slot.getId());

        assertThat(hold.providerId()).isEqualTo(expert.getId());
        assertThat(hold.track()).isEqualTo("professional");
        assertThat(hold.price()).isEqualTo(80000);
        assertThat(hold.holdExpiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(9)), Instant.now().plus(Duration.ofMinutes(11)));
    }

    @Test
    void book은_홀드된_슬롯만_예약_완료로_바꾸고_release는_다시_연다() {
        AvailabilitySlot slot = saveSlot(saveUser(UserRole.TRAINEE));
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> slotService.book(slot.getId(), sessionId))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATE);

        slotService.hold(slot.getId());
        slotService.book(slot.getId(), sessionId);
        AvailabilitySlot booked = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(booked.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(booked.getSessionId()).isEqualTo(sessionId);
        assertThat(booked.getHoldExpiresAt()).isNull();

        slotService.release(slot.getId());
        AvailabilitySlot reopened = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(reopened.getSessionId()).isNull();
    }

    @Test
    void 없는_슬롯을_잡으면_404() {
        assertThatThrownBy(() -> slotService.hold(UUID.randomUUID()))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    private User saveUser(UserRole role) {
        String uid = "slot-svc-" + UUID.randomUUID();
        User user = userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
        createdUsers.add(user.getId());
        return user;
    }

    private AvailabilitySlot saveSlot(User provider) {
        return slotRepository.saveAndFlush(AvailabilitySlot.open(provider.getId(), START, START.plus(Duration.ofMinutes(30))));
    }
}
