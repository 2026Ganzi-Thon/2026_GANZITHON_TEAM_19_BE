package com.mindcare.domain.credential.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** credentials.kind. 수련생: 재학(enrollment)·수료(completion) 증빙 / 전문가: 국가자격(national)·학회자격(society). */
@Getter
@RequiredArgsConstructor
public enum CredentialKind {
    ENROLLMENT("enrollment"),
    COMPLETION("completion"),
    NATIONAL("national"),
    SOCIETY("society");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<CredentialKind, String> {

        @Override
        public String convertToDatabaseColumn(CredentialKind item) {
            return item == null ? null : item.value;
        }

        @Override
        public CredentialKind convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(item -> item.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown CredentialKind: " + value));
        }
    }
}
