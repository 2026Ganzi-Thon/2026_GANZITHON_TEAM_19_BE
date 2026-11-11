package com.mindcare.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PAY-03 요청. payment_key는 토스 결제 성공 시에만 온다 (mock은 없어도 됨). */
public record PaymentConfirmRequest(@NotBlank @Size(max = 64) String orderId, @Size(max = 200) String paymentKey) {
}
