package com.mindcare.domain.payment.dto;

import com.mindcare.domain.payment.entity.Payment;
import com.mindcare.domain.payment.entity.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/** PAY-03 응답. */
public record PaymentConfirmResponse(UUID paymentId, UUID sessionId, PaymentStatus status, int amount, Instant paidAt) {

    public static PaymentConfirmResponse from(Payment payment) {
        return new PaymentConfirmResponse(payment.getId(), payment.getSessionId(), payment.getStatus(),
                payment.getAmount(), payment.getPaidAt());
    }
}
