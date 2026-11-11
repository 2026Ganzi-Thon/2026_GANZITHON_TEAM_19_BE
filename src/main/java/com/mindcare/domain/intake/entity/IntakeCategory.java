package com.mindcare.domain.intake.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IntakeCategory {
    CAREER("career"), BURNOUT("burnout"), RELATIONSHIP("relationship"), ROMANCE("romance"),
    FAMILY("family"), SELF("self"), OTHER("other");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<IntakeCategory, String> {
        @Override public String convertToDatabaseColumn(IntakeCategory value) { return value == null ? null : value.value; }
        @Override public IntakeCategory convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown intake category: " + value));
        }
    }
}
