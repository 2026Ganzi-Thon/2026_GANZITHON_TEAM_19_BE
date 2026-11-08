package com.mindcare.domain.intake.entity;

import com.mindcare.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "intakes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Intake {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private User client;

    @Column(nullable = false) private IntakeCategory category;
    @Column(name = "text_enc", nullable = false) private byte[] textEnc;
    @Column(nullable = false) private boolean shareRaw;
    @Column(nullable = false, columnDefinition = "text[]") private List<String> preferredTimes;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, List<String>> aiTags;
    @Column(nullable = false) private String aiStatus;
    @Column(nullable = false) private RiskLevel riskLevel;
    @Column(nullable = false) private boolean notifyWhenAvailable;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;

    @Builder
    private Intake(User client, IntakeCategory category, byte[] textEnc, boolean shareRaw, List<String> preferredTimes,
            Map<String, List<String>> aiTags, String aiStatus, RiskLevel riskLevel, boolean notifyWhenAvailable) {
        this.client = client; this.category = category; this.textEnc = textEnc; this.shareRaw = shareRaw;
        this.preferredTimes = List.copyOf(preferredTimes); this.aiTags = Map.copyOf(aiTags); this.aiStatus = aiStatus;
        this.riskLevel = riskLevel; this.notifyWhenAvailable = notifyWhenAvailable;
    }
}
