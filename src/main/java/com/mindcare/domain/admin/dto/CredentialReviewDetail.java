package com.mindcare.domain.admin.dto;

import com.mindcare.domain.credential.entity.CredentialKind;
import com.mindcare.domain.credential.entity.CredentialStatus;
import com.mindcare.domain.credential.entity.VerifyMethod;
import com.mindcare.domain.credential.entity.VerifyResult;
import com.mindcare.domain.user.dto.ProviderProfileResponse;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADM-02 응답. 프로필(실명 복호화) + 자격 전체(자격번호 원문, 서류 보기 URL 10분). 열람은 audit_logs에 남는다. */
public record CredentialReviewDetail(
        UUID userId,
        UserRole role,
        UserStatus status,
        String nickname,
        ProviderProfileResponse profile,
        List<Item> credentials) {

    public record Item(
            UUID id,
            CredentialKind kind,
            String certName,
            String certNumber,
            LocalDate issuedOn,
            String fileUrl,
            Map<String, Object> ocrResult,
            boolean userCorrected,
            VerifyMethod verifyMethod,
            VerifyResult verifyResult,
            Map<String, Object> verifyRaw,
            CredentialStatus status,
            String rejectReason,
            Instant reviewedAt,
            Instant createdAt) {
    }
}
