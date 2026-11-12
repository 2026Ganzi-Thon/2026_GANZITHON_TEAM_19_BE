package com.mindcare.domain.credential.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** credentials.status. draft → (CRD-04) submitted → (ADM-03) approved / rejected. rejected는 고쳐서 다시 낼 수 있다. */
@Getter
@RequiredArgsConstructor
public enum CredentialStatus {
    DRAFT("draft"),
    SUBMITTED("submitted"),
    APPROVED("approved"),
    REJECTED("rejected");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<CredentialStatus, String> {

        @Override
        public String convertToDatabaseColumn(CredentialStatus item) {
            return item == null ? null : item.value;
        }

        @Override
        public CredentialStatus convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(item -> item.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown CredentialStatus: " + value));
        }
    }
}
