package com.mindcare.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * users.status (TRD 4-1 계정 상태). DB·JSON에는 소문자 값으로 들어간다.
 */
@Getter
@RequiredArgsConstructor
public enum UserStatus {
    ACTIVE("active"),
    PENDING_REVIEW("pending_review"),
    REJECTED("rejected"),
    SUSPENDED("suspended"),
    BANNED("banned"),
    RESTRICTED("restricted"),
    WITHDRAWN("withdrawn");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<UserStatus, String> {

        @Override
        public String convertToDatabaseColumn(UserStatus status) {
            return status == null ? null : status.value;
        }

        @Override
        public UserStatus convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(status -> status.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown status: " + value));
        }
    }
}
