package com.mindcare.domain.payment.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * payments.status (TRD 15-10): ready → paid (PAY-03) → refunded (PAY-04) / ready → failed / ready → cancelled (홀드 만료).
 */
@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    READY("ready"), PAID("paid"), CANCELLED("cancelled"), REFUNDED("refunded"), FAILED("failed");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<PaymentStatus, String> {
        @Override public String convertToDatabaseColumn(PaymentStatus value) { return value == null ? null : value.value; }
        @Override public PaymentStatus convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown payment status: " + value));
        }
    }
}
