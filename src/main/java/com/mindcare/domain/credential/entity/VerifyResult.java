package com.mindcare.domain.credential.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** credentials.verify_result. 국가자격 진위 확인 결과 (확인 안 했으면 none). */
@Getter
@RequiredArgsConstructor
public enum VerifyResult {
    MATCH("match"),
    MISMATCH("mismatch"),
    UNAVAILABLE("unavailable"),
    NONE("none");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<VerifyResult, String> {

        @Override
        public String convertToDatabaseColumn(VerifyResult item) {
            return item == null ? null : item.value;
        }

        @Override
        public VerifyResult convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(item -> item.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VerifyResult: " + value));
        }
    }
}
