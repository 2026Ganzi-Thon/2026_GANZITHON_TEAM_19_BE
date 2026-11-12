package com.mindcare.domain.settlement.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** settlements.status. locked = 연계 슈퍼비전 대기, held = 스트라이크 보류, pending = 지급 대기, paid = 지급 완료. */
@Getter
@RequiredArgsConstructor
public enum SettlementStatus {
    LOCKED("locked"),
    HELD("held"),
    PENDING("pending"),
    PAID("paid");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<SettlementStatus, String> {

        @Override
        public String convertToDatabaseColumn(SettlementStatus item) {
            return item == null ? null : item.value;
        }

        @Override
        public SettlementStatus convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(item -> item.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown SettlementStatus: " + value));
        }
    }
}
