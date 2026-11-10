package com.mindcare.domain.payment.dto;

import com.mindcare.domain.payment.entity.Payment;
import com.mindcare.domain.payment.entity.PaymentStatus;
import java.util.UUID;

/** PAY-04 응답. */
public record RefundResponse(UUID paymentId, PaymentStatus status, Integer refundAmount) {

    public static RefundResponse from(Payment payment) {
        return new RefundResponse(payment.getId(), payment.getStatus(), payment.getRefundAmount());
    }
}
