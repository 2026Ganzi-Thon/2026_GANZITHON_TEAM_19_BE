package com.mindcare.domain.payment.service;

import com.mindcare.domain.admin.service.PriceConfigService;
import com.mindcare.domain.payment.dto.PaymentConfirmRequest;
import com.mindcare.domain.payment.dto.PaymentConfirmResponse;
import com.mindcare.domain.payment.dto.PaymentPrepareResponse;
import com.mindcare.domain.payment.dto.RefundResponse;
import com.mindcare.domain.payment.entity.Payment;
import com.mindcare.domain.payment.entity.PaymentStatus;
import com.mindcare.domain.payment.repository.PaymentRepository;
import com.mindcare.domain.payment.repository.PaymentSessionReader;
import com.mindcare.domain.payment.repository.PaymentSessionReader.SessionForPayment;
import com.mindcare.domain.slot.service.SlotService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의 결제·환불 (TRD 13-3 PAY-02~04, 15-1, 15-7, 15-12).
 * 결제 확정 시 세션 상태 변경은 PaymentConfirmedEvent로 세션 도메인에 맡기고, 슬롯은 SlotService.book으로 바꾼다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentSessionReader sessionReader;
    private final IdentityService identityService;
    private final SlotService slotService;
    private final PriceConfigService priceConfigService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // PAY-02: 본인인증이 없으면 409, 홀드가 끝났으면 410. 같은 세션의 ready 결제가 있으면 그걸 돌려준다
    @Transactional
    public PaymentPrepareResponse prepare(AuthUser user, UUID sessionId, String idempotencyKey) {
        user.requireRole(UserRole.CLIENT);
        SessionForPayment session = getOwnPendingSession(user, sessionId);
        if (!identityService.isVerified(user.id())) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("reason", "identity_required"));
        }
        slotService.assertHoldActive(session.slotId());

        Payment payment = paymentRepository.findBySessionIdForUpdate(sessionId)
                .orElseGet(() -> paymentRepository.save(
                        Payment.ready(sessionId, user.id(), session.price(), idempotencyKey)));
        if (payment.getStatus() != PaymentStatus.READY) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", payment.getStatus().getValue()));
        }
        return PaymentPrepareResponse.from(payment);
    }

    // PAY-03: mock은 바로 paid. 세션 confirmed(이벤트) + 슬롯 booked를 한 트랜잭션에서
    @Transactional
    public PaymentConfirmResponse confirm(AuthUser user, PaymentConfirmRequest request) {
        user.requireRole(UserRole.CLIENT);
        Payment payment = paymentRepository.findByOrderIdForUpdate(request.orderId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!payment.getClientId().equals(user.id())) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        if (payment.getStatus() != PaymentStatus.READY) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", payment.getStatus().getValue()));
        }
        SessionForPayment session = getOwnPendingSession(user, payment.getSessionId());
        slotService.assertHoldActive(session.slotId());

        payment.markPaid(request.paymentKey(), clock.instant());
        slotService.book(session.slotId(), session.id());
        events.publishEvent(new PaymentConfirmedEvent(session.id(), payment.getId()));
        return PaymentConfirmResponse.from(payment);
    }

    // PAY-04 어드민 수동 환불. amount가 없으면 전액
    @Transactional
    public RefundResponse refundByAdmin(AuthUser user, UUID paymentId, Integer amount) {
        user.requireRole(UserRole.ADMIN);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        int refundAmount = amount == null ? payment.getAmount() : amount;
        if (refundAmount < 0 || refundAmount > payment.getAmount()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "amount", "reason", "out_of_range"))));
        }
        refund(payment, refundAmount);
        return RefundResponse.from(payment);
    }

    /**
     * 세션 취소·노쇼·연계 거절 때 세션 도메인에서 호출한다 (TRD 15-7 환불 규칙).
     * 내담자 취소는 시작 24시간 전까지 100%, 이후 0%. 그 외(상담사 취소·노쇼·정지·연계 거절)는 100%.
     * 결제가 없거나 paid가 아니면 아무것도 하지 않고 0을 돌려준다. 돌려준 금액만큼 환불됐다는 뜻이다.
     */
    @Transactional
    public int refundForCancellation(UUID sessionId, RefundReason reason) {
        Payment payment = paymentRepository.findBySessionIdForUpdate(sessionId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PAID) {
            return 0;
        }
        int refundAmount = payment.getAmount();
        if (reason == RefundReason.CLIENT_CANCEL) {
            Instant start = sessionReader.find(sessionId).orElseThrow().scheduledStart();
            Duration fullRefundWindow = Duration.ofHours(priceConfigService.fullRefundHours());
            if (clock.instant().isAfter(start.minus(fullRefundWindow))) {
                return 0;
            }
        }
        refund(payment, refundAmount);
        return refundAmount;
    }

    private void refund(Payment payment, int refundAmount) {
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", payment.getStatus().getValue()));
        }
        // mock 결제라 PG 취소 호출은 없음. toss 연동 시 여기서 취소 API 호출 (TRD 17-1)
        payment.markRefunded(refundAmount, clock.instant());
    }

    private SessionForPayment getOwnPendingSession(AuthUser user, UUID sessionId) {
        SessionForPayment session = sessionReader.find(sessionId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!session.clientId().equals(user.id())) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        if (!session.isPendingPayment()) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", session.status()));
        }
        return session;
    }

    public enum RefundReason {
        CLIENT_CANCEL, PROVIDER_CANCEL, NO_SHOW, ACCOUNT_SANCTION, REFERRAL_DECLINED
    }
}
