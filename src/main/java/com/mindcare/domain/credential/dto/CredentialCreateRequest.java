package com.mindcare.domain.credential.dto;

import com.mindcare.domain.credential.entity.CredentialKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * CRD-02 요청. 수련생은 enrollment·completion, 전문가는 national·society.
 * 서류(file_key)는 국가자격 진위 확인(verify=true)을 할 때만 생략할 수 있다.
 */
public record CredentialCreateRequest(
        @NotNull CredentialKind kind,
        @NotBlank @Size(max = 100) String certName,
        @Size(max = 50) String certNumber,
        LocalDate issuedOn,
        @Size(max = 200) String fileKey,
        Boolean verify) {
}
