package com.mindcare.domain.report.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.*;

@Entity @Table(name = "reports") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7) private UUID id;
    @Column(nullable = false) private String scope;
    private UUID sessionId; private UUID referralId;
    @Column(nullable = false) private UUID clientId; private UUID traineeId;
    @Column(nullable = false) private short version = 1;
    @Column(nullable = false) private String status = "generating";
    private byte[] contentEnc; private byte[] clientSummaryEnc; private String riskLevel; private Instant readyAt;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    public static Report generatingForSession(UUID sessionId, UUID clientId, UUID traineeId) { Report r = new Report(); r.scope = "session"; r.sessionId = sessionId; r.clientId = clientId; r.traineeId = traineeId; return r; }
    public void markReady(byte[] content, byte[] summary, String level) { status = "ready"; contentEnc = content; clientSummaryEnc = summary; riskLevel = level; readyAt = Instant.now(); }
    public void fail() { status = "failed"; }
    public void regenerate() { status = "generating"; }
}
