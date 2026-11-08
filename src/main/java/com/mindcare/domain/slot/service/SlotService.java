package com.mindcare.domain.slot.service;

import com.mindcare.domain.admin.service.PriceConfigService;
import com.mindcare.domain.admin.service.PriceConfigService.TrackPricing;
import com.mindcare.domain.slot.dto.MySlotResponse;
import com.mindcare.domain.slot.dto.OpenSlotResponse;
import com.mindcare.domain.slot.dto.SlotCreateRequest;
import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.entity.SlotStatus;
import com.mindcare.domain.slot.repository.SlotRepository;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담 가능 시간 (TRD 13-2 SLT-01~04, 15-1, 15-12).
 * 예약(SES-01)·결제(PAY-03)·취소에서는 hold / book / release를 호출한다. availability_slots를 직접 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SlotService {

    static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    static final Duration PUBLIC_RANGE_LIMIT = Duration.ofDays(14);
    private static final Duration DEFAULT_MY_RANGE = Duration.ofDays(28);

    private final SlotRepository slotRepository;
    private final UserService userService;
    private final PriceConfigService priceConfigService;
    private final Clock clock;

    // SLT-01
    public List<MySlotResponse> listMine(AuthUser user, Instant from, Instant to) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT);
        Instant start = from != null ? from : clock.instant();
        Instant end = to != null ? to : start.plus(DEFAULT_MY_RANGE);
        validateRange(start, end);
        return slotRepository.findByProviderIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAt(user.id(), start, end)
                .stream().map(MySlotResponse::from).toList();
    }

    // SLT-02: 길이는 트랙 설정값. 과거 시각은 422 VALIDATION_ERROR, 기존·요청 내 겹침은 422 SLOT_OVERLAP
    @Transactional
    public List<MySlotResponse> create(AuthUser user, SlotCreateRequest request) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT);
        Duration length = Duration.ofMinutes(priceConfigService.trackPricing(trackOf(user.role())).durationMinutes());
        Instant now = clock.instant();

        List<AvailabilitySlot> slots = new ArrayList<>();
        for (Instant start : request.starts()) {
            if (!start.isAfter(now)) {
                throw new AppException(ErrorCode.VALIDATION_ERROR,
                        Map.of("fields", List.of(Map.of("field", "starts", "reason", "past"))));
            }
            for (int week = 0; week <= request.repeatWeeksOrZero(); week++) {
                Instant occurrence = start.plus(Duration.ofDays(7L * week));
                slots.add(AvailabilitySlot.open(user.id(), occurrence, occurrence.plus(length)));
            }
        }
        try {
            return slotRepository.saveAllAndFlush(slots).stream()
                    .map(MySlotResponse::from)
                    .sorted((a, b) -> a.startAt().compareTo(b.startAt()))
                    .toList();
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.SLOT_OVERLAP);
        }
    }

    // SLT-03: 내담자용. 활성 상담사의 open 슬롯만, 최대 14일
    public List<OpenSlotResponse> listOpen(AuthUser user, UUID providerId, Instant from, Instant to) {
        user.requireRole(UserRole.CLIENT);
        User provider = userService.getById(providerId);
        if (trackOrNull(provider.getRole()) == null || provider.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        Instant now = clock.instant();
        Instant start = from != null && from.isAfter(now) ? from : now;
        Instant end = to != null ? to : start.plus(PUBLIC_RANGE_LIMIT);
        validateRange(start, end);
        if (Duration.between(start, end).compareTo(PUBLIC_RANGE_LIMIT) > 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "to", "reason", "max_14_days"))));
        }
        return slotRepository
                .findByProviderIdAndStatusAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAt(
                        providerId, SlotStatus.OPEN, start, end)
                .stream().map(OpenSlotResponse::from).toList();
    }

    // SLT-04: 예약 대기·완료된 슬롯은 409 SLOT_BOOKED
    @Transactional
    public void delete(AuthUser user, UUID slotId) {
        user.requireRole(UserRole.TRAINEE, UserRole.EXPERT);
        AvailabilitySlot slot = slotRepository.findById(slotId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!slot.getProviderId().equals(user.id())) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new AppException(ErrorCode.SLOT_BOOKED);
        }
        slotRepository.delete(slot);
    }

    /**
     * 예약 생성(SES-01)에서 호출. 슬롯을 10분 동안 잡는다. 다른 사람이 먼저 잡았으면 409 SLOT_TAKEN.
     * 호출하는 쪽 트랜잭션 안에서 실행된다 (세션 생성이 실패하면 홀드도 같이 롤백).
     */
    @Transactional
    public SlotHold hold(UUID slotId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(HOLD_DURATION);
        if (slotRepository.hold(slotId, expiresAt, now) == 0) {
            if (!slotRepository.existsById(slotId)) {
                throw new AppException(ErrorCode.NOT_FOUND);
            }
            throw new AppException(ErrorCode.SLOT_TAKEN);
        }
        AvailabilitySlot slot = slotRepository.findById(slotId).orElseThrow();
        User provider = userService.getById(slot.getProviderId());
        String track = trackOf(provider.getRole());
        TrackPricing pricing = priceConfigService.trackPricing(track);
        return new SlotHold(slot.getId(), slot.getProviderId(), track, slot.getStartAt(), slot.getEndAt(),
                expiresAt, pricing.price());
    }

    /** 결제 확정(PAY-03)에서 호출. held → booked. 홀드 상태가 아니면 409 INVALID_STATE. */
    @Transactional
    public void book(UUID slotId, UUID sessionId) {
        if (slotRepository.book(slotId, sessionId) == 0) {
            throw new AppException(ErrorCode.INVALID_STATE);
        }
    }

    /** 홀드 만료·세션 취소에서 호출. 슬롯을 다시 open으로. 이미 open이면 아무것도 안 한다. */
    @Transactional
    public void release(UUID slotId) {
        slotRepository.release(slotId);
    }

    private static void validateRange(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "to", "reason", "before_from"))));
        }
    }

    private static String trackOf(UserRole role) {
        String track = trackOrNull(role);
        if (track == null) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        return track;
    }

    private static String trackOrNull(UserRole role) {
        return switch (role) {
            case TRAINEE -> PriceConfigService.COACHING;
            case EXPERT -> PriceConfigService.PROFESSIONAL;
            default -> null;
        };
    }
}
