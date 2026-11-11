package com.mindcare.domain.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PAY-04 요청. amount가 없으면 전액 환불. */
public record RefundRequest(@Min(0) Integer amount, @NotBlank @Size(max = 200) String reason) {
}
