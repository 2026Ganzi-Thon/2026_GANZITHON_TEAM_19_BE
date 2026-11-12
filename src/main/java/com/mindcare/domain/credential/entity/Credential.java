package com.mindcare.domain.credential.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * TRD 11-1 credentials. 자격·재학 증빙 한 건. 자격번호는 암호화해서 저장한다.
 */
@Entity
@Table(name = "credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Credential {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private CredentialKind kind;

    @Column(nullable = false)
    private String certName;

    @Column(name = "cert_number_enc")
    private byte[] certNumberEnc;

    private LocalDate issuedOn;

    private String fileKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> ocrResult;

    @Column(nullable = false)
    private boolean userCorrected;

    private VerifyMethod verifyMethod;

    @Column(nullable = false)
    private VerifyResult verifyResult = VerifyResult.NONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> verifyRaw;

    @Column(nullable = false)
    private CredentialStatus status = CredentialStatus.DRAFT;

    private String rejectReason;

    private UUID reviewedBy;

    private Instant reviewedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static Credential draft(UUID userId, CredentialKind kind, String certName, byte[] certNumberEnc,
            LocalDate issuedOn, String fileKey) {
        Credential credential = new Credential();
        credential.userId = userId;
        credential.kind = kind;
        credential.certName = certName;
        credential.certNumberEnc = certNumberEnc;
        credential.issuedOn = issuedOn;
        credential.fileKey = fileKey;
        credential.verifyMethod = fileKey == null ? null : VerifyMethod.MANUAL;
        return credential;
    }

    public boolean isEditable() {
        return status == CredentialStatus.DRAFT || status == CredentialStatus.REJECTED;
    }

    public void recordVerification(VerifyResult result, Map<String, Object> raw) {
        this.verifyMethod = VerifyMethod.API;
        this.verifyResult = result;
        this.verifyRaw = raw;
    }

    /**
     * CRD-05. 사용자가 직접 고친 값은 user_corrected=true로 남겨 어드민이 알 수 있게 한다.
     * 반려된 건을 고치면 다시 draft가 되고 반려 사유는 지운다 (다음 CRD-04에서 함께 제출).
     */
    public void correct(String certName, byte[] certNumberEnc, boolean certNumberChanged, LocalDate issuedOn,
            boolean issuedOnChanged) {
        if (certName != null) {
            this.certName = certName;
        }
        if (certNumberChanged) {
            this.certNumberEnc = certNumberEnc;
            // 번호가 바뀌면 이전 진위 확인 결과는 의미가 없다
            this.verifyResult = VerifyResult.NONE;
            this.verifyRaw = null;
        }
        if (issuedOnChanged) {
            this.issuedOn = issuedOn;
        }
        this.userCorrected = true;
        this.status = CredentialStatus.DRAFT;
        this.rejectReason = null;
    }

    public void submit() {
        this.status = CredentialStatus.SUBMITTED;
    }

    public void approve(UUID adminId) {
        this.status = CredentialStatus.APPROVED;
        this.rejectReason = null;
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }

    public void reject(UUID adminId, String reason) {
        this.status = CredentialStatus.REJECTED;
        this.rejectReason = reason;
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }
}
