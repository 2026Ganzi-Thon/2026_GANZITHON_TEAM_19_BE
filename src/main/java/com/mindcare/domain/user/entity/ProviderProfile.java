package com.mindcare.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * TRD 11-1 provider_profiles. 수련생·전문가 한 명당 한 행 (PK = users.id).
 * 실명·소속은 암호화해서 저장하고, 내담자에게는 활동명(display_name)만 보인다.
 */
@Entity
@Table(name = "provider_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderProfile {

    @Id
    private UUID userId;

    @Column(nullable = false)
    private ProviderTrack track;

    @Column(nullable = false, unique = true)
    private String displayName;

    @Column(name = "real_name_enc", nullable = false)
    private byte[] realNameEnc;

    private ServiceLabel serviceLabel;

    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> specialties = new ArrayList<>();

    @Column(nullable = false)
    private String bio = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Career> career = new ArrayList<>();

    @Column(name = "school_enc")
    private byte[] schoolEnc;

    @Column(nullable = false)
    private boolean acceptsSupervision = true;

    private BigDecimal ratingAvg;

    @Column(nullable = false)
    private int ratingCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static ProviderProfile create(UUID userId, ProviderTrack track) {
        ProviderProfile profile = new ProviderProfile();
        profile.userId = userId;
        profile.track = track;
        return profile;
    }

    /** USR-06은 PUT이라 매번 전체를 덮어쓴다. 평점은 리뷰에서만 바뀐다. */
    public void update(String displayName, byte[] realNameEnc, ServiceLabel serviceLabel, List<String> specialties,
            String bio, List<Career> career, byte[] schoolEnc, boolean acceptsSupervision) {
        this.displayName = displayName;
        this.realNameEnc = realNameEnc;
        this.serviceLabel = serviceLabel;
        this.specialties = new ArrayList<>(specialties);
        this.bio = bio;
        this.career = new ArrayList<>(career);
        this.schoolEnc = schoolEnc;
        this.acceptsSupervision = acceptsSupervision;
    }
}
