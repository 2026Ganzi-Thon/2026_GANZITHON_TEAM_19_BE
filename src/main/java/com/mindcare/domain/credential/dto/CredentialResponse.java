package com.mindcare.domain.credential.dto;

import com.mindcare.domain.credential.entity.Credential;
import com.mindcare.domain.credential.entity.CredentialKind;
import com.mindcare.domain.credential.entity.CredentialStatus;
import com.mindcare.domain.credential.entity.VerifyMethod;
import com.mindcare.domain.credential.entity.VerifyResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * CRD-02·03·05 응답 (본인용). 자격번호는 뒤 4자리만 보여준다.
 */
public record CredentialResponse(
        UUID id,
        CredentialKind kind,
        String certName,
        String certNumberMasked,
        LocalDate issuedOn,
        boolean hasFile,
        Map<String, Object> ocrResult,
        boolean userCorrected,
        VerifyMethod verifyMethod,
        VerifyResult verifyResult,
        CredentialStatus status,
        String rejectReason,
        Instant createdAt) {

    public static CredentialResponse of(Credential credential, String certNumber) {
        return new CredentialResponse(
                credential.getId(),
                credential.getKind(),
                credential.getCertName(),
                mask(certNumber),
                credential.getIssuedOn(),
                credential.getFileKey() != null,
                credential.getOcrResult(),
                credential.isUserCorrected(),
                credential.getVerifyMethod(),
                credential.getVerifyResult(),
                credential.getStatus(),
                credential.getRejectReason(),
                credential.getCreatedAt());
    }

    static String mask(String value) {
        if (value == null) {
            return null;
        }
        int visible = Math.min(4, value.length());
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }
}
