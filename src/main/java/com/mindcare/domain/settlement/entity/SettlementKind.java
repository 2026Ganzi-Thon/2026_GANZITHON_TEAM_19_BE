package com.mindcare.domain.settlement.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** settlements.kind. session = 상담 1회, supervision = 슈퍼비전 1건 (작성한 전문가 몫). */
@Getter
@RequiredArgsConstructor
public enum SettlementKind {
    SESSION("session"),
    SUPERVISION("supervision");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<SettlementKind, String> {

        @Override
        public String convertToDatabaseColumn(SettlementKind item) {
            return item == null ? null : item.value;
        }

        @Override
        public SettlementKind convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(item -> item.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown SettlementKind: " + value));
        }
    }
}
