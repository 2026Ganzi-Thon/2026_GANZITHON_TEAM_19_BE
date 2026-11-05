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
}
