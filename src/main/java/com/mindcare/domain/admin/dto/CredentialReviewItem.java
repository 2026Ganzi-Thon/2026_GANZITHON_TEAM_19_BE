package com.mindcare.domain.admin.dto;

import com.mindcare.domain.credential.entity.CredentialKind;
import com.mindcare.domain.credential.entity.VerifyResult;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADM-01 목록 한 줄 (심사 대기 신청자). 실명·자격번호는 상세(ADM-02)에서만. */
public record CredentialReviewItem(
        UUID userId,
        UserRole role,
        UserStatus status,
        String nickname,
        String displayName,
        Instant submittedAt,
        List<Summary> credentials) {

    public record Summary(CredentialKind kind, String certName, VerifyResult verifyResult, boolean userCorrected) {
    }
}
