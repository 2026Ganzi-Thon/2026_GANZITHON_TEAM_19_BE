package com.mindcare.domain.intake.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RiskLevel {
    NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high"), CRITICAL("critical");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<RiskLevel, String> {
        @Override public String convertToDatabaseColumn(RiskLevel value) { return value == null ? null : value.value; }
        @Override public RiskLevel convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown risk level: " + value));
        }
    }
}
