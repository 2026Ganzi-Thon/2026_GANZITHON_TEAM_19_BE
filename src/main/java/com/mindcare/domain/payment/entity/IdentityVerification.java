package com.mindcare.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * TRD 11-1 identity_verifications. 결제용 본인인증 결과만 따로 보관한다 (Zero-PII).
 * 이름·생년월일·전화번호는 암호문으로만 저장하고, 상담 화면·API 응답에는 절대 넣지 않는다.
 */
@Entity
@Table(name = "identity_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdentityVerification {

    @Id
    private UUID userId;

    @Column(nullable = false)
    private byte[] nameEnc;

    @Column(nullable = false)
    private byte[] birthEnc;

    @Column(nullable = false)
    private byte[] phoneEnc;

    @Column(nullable = false, unique = true)
    private byte[] phoneHmac;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static IdentityVerification of(UUID userId, byte[] nameEnc, byte[] birthEnc, byte[] phoneEnc,
            byte[] phoneHmac, String provider, Instant verifiedAt) {
        IdentityVerification verification = new IdentityVerification();
        verification.userId = userId;
        verification.update(nameEnc, birthEnc, phoneEnc, phoneHmac, provider, verifiedAt);
        return verification;
    }

    public void update(byte[] nameEnc, byte[] birthEnc, byte[] phoneEnc, byte[] phoneHmac, String provider,
            Instant verifiedAt) {
        this.nameEnc = nameEnc;
        this.birthEnc = birthEnc;
        this.phoneEnc = phoneEnc;
        this.phoneHmac = phoneHmac;
        this.provider = provider;
        this.verifiedAt = verifiedAt;
    }
}
