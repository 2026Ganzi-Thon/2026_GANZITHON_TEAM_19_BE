package com.mindcare.domain.payment.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** PAY-02 요청. */
public record PaymentPrepareRequest(@NotNull UUID sessionId) {
}
