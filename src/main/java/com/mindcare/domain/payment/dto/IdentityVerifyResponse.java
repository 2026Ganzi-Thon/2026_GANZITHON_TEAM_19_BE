package com.mindcare.domain.payment.dto;

/** PAY-01 응답. 개인정보는 넣지 않는다 (TRD FR-BKG-03). */
public record IdentityVerifyResponse(boolean verified) {
}
