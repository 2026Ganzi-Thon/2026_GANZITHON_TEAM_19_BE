package com.mindcare.domain.credential.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** CRD-05 요청. 보낸 필드만 바꾼다 (null = 그대로). */
public record CredentialUpdateRequest(
        @Size(min = 1, max = 100) String certName,
        @Size(min = 1, max = 50) String certNumber,
        LocalDate issuedOn) {
}
