package com.mindcare.domain.session.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

public enum MoodPhase {
    BEFORE("before"), AFTER("after");
    private final String value;
    MoodPhase(String value) { this.value = value; }
    @JsonValue public String getValue() { return value; }
    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<MoodPhase, String> {
        public String convertToDatabaseColumn(MoodPhase value) { return value == null ? null : value.value; }
        public MoodPhase convertToEntityAttribute(String value) { return value == null ? null : Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst().orElseThrow(); }
    }
}
