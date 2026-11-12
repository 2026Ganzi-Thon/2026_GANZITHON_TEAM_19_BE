package com.mindcare.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/** TRD 11-1 device_tokens. 브라우저(기기)마다 FCM 토큰 하나. 토큰은 전체에서 유일하다. */
@Entity
@Table(name = "device_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true, updatable = false)
    private String token;

    @Column(nullable = false)
    private String platform;

    private Instant lastSeenAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static DeviceToken of(UUID userId, String token, String platform) {
        DeviceToken deviceToken = new DeviceToken();
        deviceToken.userId = userId;
        deviceToken.token = token;
        deviceToken.platform = platform;
        deviceToken.lastSeenAt = Instant.now();
        return deviceToken;
    }

    /** 같은 브라우저에서 다른 계정으로 로그인하면 토큰 주인을 바꾼다 (이전 계정 알림이 가지 않게). */
    public void touch(UUID userId, String platform) {
        this.userId = userId;
        this.platform = platform;
        this.lastSeenAt = Instant.now();
    }
}
