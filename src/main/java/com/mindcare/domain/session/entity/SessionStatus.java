package com.mindcare.domain.session.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

public enum SessionStatus {
    PENDING_PAYMENT("pending_payment"), CONFIRMED("confirmed"), IN_PROGRESS("in_progress"),
    COMPLETED("completed"), CANCELLED("cancelled"), NO_SHOW("no_show");
    private final String value;
    SessionStatus(String value) { this.value = value; }
    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<SessionStatus, String> {
        public String convertToDatabaseColumn(SessionStatus value) { return value == null ? null : value.value; }
        public SessionStatus convertToEntityAttribute(String value) { return value == null ? null : Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst().orElseThrow(); }
    }
}
