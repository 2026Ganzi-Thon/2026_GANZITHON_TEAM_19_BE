package com.mindcare.domain.payment.dto;

import com.mindcare.domain.payment.entity.Payment;

/** PAY-02 응답. client_key는 토스 결제위젯용이라 mock에서는 null. */
public record PaymentPrepareResponse(String orderId, int amount, String provider, String clientKey) {

    public static PaymentPrepareResponse from(Payment payment) {
        return new PaymentPrepareResponse(payment.getOrderId(), payment.getAmount(), payment.getProvider(), null);
    }
}
