package com.mindcare.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * TRD 11-1 users. 이메일·비밀번호는 Firebase에만 있고 여기에는 저장하지 않는다 (Zero-PII).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String firebaseUid;

    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(nullable = false)
    private UserStatus status;

    private Instant suspendedUntil;

    private Instant restrictedUntil;

    @Column(nullable = false)
    private boolean ethicsTrainingRequired;

    @Column(nullable = false)
    private boolean trainingDataOptIn;

    @Column(nullable = false)
    private boolean marketingOptIn;

    private Instant withdrawnAt;

    /** 강제 로그아웃 시각 (TRD 6-1). 실제 차단은 Firebase revokeRefreshTokens + check_revoked 검증이 한다. */
    private Instant tokensRevokedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Builder
    private User(String firebaseUid, UserRole role, String nickname, UserStatus status,
            boolean trainingDataOptIn, boolean marketingOptIn) {
        this.firebaseUid = firebaseUid;
        this.role = role;
        this.nickname = nickname;
        this.status = status;
        this.trainingDataOptIn = trainingDataOptIn;
        this.marketingOptIn = marketingOptIn;
    }

    /** USR-02. 규칙·중복 검사는 UserService에서. */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /** USR-07. 정지 해제는 suspended_until이 지나야 하고, 이 플래그는 배정 재개 조건 중 하나다. */
    public void completeEthicsTraining() {
        this.ethicsTrainingRequired = false;
    }

    // ---- 자격 심사 (CRD-04, ADM-03). 상태 전이 규칙은 UserService에서 확인한다

    /** 자격 서류를 (다시) 제출하면 심사 대기로. */
    public void markPendingReview() {
        this.status = UserStatus.PENDING_REVIEW;
    }

    public void approveReview() {
        this.status = UserStatus.ACTIVE;
    }

    public void rejectReview() {
        this.status = UserStatus.REJECTED;
    }

    // ---- 어드민 상태 변경 (ADM-05). 허용 전이는 UserService에서 확인한다

    /** until은 suspended·restricted일 때만 의미가 있다 (null이면 해제할 때까지). */
    public void changeStatusByAdmin(UserStatus status, Instant until) {
        this.status = status;
        this.suspendedUntil = status == UserStatus.SUSPENDED ? until : null;
        this.restrictedUntil = status == UserStatus.RESTRICTED ? until : null;
    }

    public void revokeTokens(Instant now) {
        this.tokensRevokedAt = now;
    }
}
