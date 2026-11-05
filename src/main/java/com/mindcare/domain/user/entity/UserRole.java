package com.mindcare.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * users.role. DB·JSON에는 소문자 값으로 들어간다 (TRD 11-1 CHECK).
 */
@Getter
@RequiredArgsConstructor
public enum UserRole {
    CLIENT("client"),
    TRAINEE("trainee"),
    EXPERT("expert"),
    ADMIN("admin");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<UserRole, String> {

        @Override
        public String convertToDatabaseColumn(UserRole role) {
            return role == null ? null : role.value;
        }

        @Override
        public UserRole convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(role -> role.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + value));
        }
    }
}
