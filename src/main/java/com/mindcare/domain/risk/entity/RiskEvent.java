package com.mindcare.domain.risk.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "risk_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskEvent {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7) private UUID id;
    @Column(nullable = false) private String source;
    private UUID sessionId;
    private UUID messageId;
    @Column(nullable = false) private UUID clientId;
    private UUID providerId;
    @Column(nullable = false) private String level;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> ruleHits;
    @Column(nullable = false) private byte[] evidenceEnc;
    @Column(nullable = false) private String status = "open";
    private UUID ackedBy;
    private Instant ackedAt;
    private UUID resolvedBy;
    private Instant resolvedAt;
    private byte[] resolutionNoteEnc;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;

    public RiskEvent(String source, UUID sessionId, UUID messageId, UUID clientId, UUID providerId,
                     String level, List<String> ruleHits, byte[] evidenceEnc) {
        this.source = source; this.sessionId = sessionId; this.messageId = messageId;
        this.clientId = clientId; this.providerId = providerId; this.level = level;
        this.ruleHits = List.copyOf(ruleHits); this.evidenceEnc = evidenceEnc;
    }
    public void acknowledge(UUID actorId) {
        if (!"open".equals(status)) throw new IllegalStateException();
        status = "acknowledged"; ackedBy = actorId; ackedAt = Instant.now();
    }
    public void resolve(UUID actorId, byte[] note) {
        if ("resolved".equals(status)) throw new IllegalStateException();
        status = "resolved"; resolvedBy = actorId; resolvedAt = Instant.now(); resolutionNoteEnc = note;
    }
}
