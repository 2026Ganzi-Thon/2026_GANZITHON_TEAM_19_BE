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
    private byte[] contentEnc; private byte[] clientSummaryEnc; private byte[] digestEnc; private String riskLevel; private Instant readyAt;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    public static Report generatingForSession(UUID sessionId, UUID clientId, UUID traineeId) { Report r = new Report(); r.scope = "session"; r.sessionId = sessionId; r.clientId = clientId; r.traineeId = traineeId; return r; }
    public static Report generatingForReferral(UUID referralId, UUID clientId) { Report r = new Report(); r.scope = "referral"; r.referralId = referralId; r.clientId = clientId; return r; }
    /** 세션 리포트의 digest(16-2-1): 연계 리포트 재료. */
    public void storeDigest(byte[] digest) { digestEnc = digest; }
    /** 연계 리포트의 포함 회기를 바꾸면 버전을 올리고 다시 생성한다. */
    public void sourcesChanged() { version++; status = "generating"; }
    public void markReady(byte[] content, byte[] summary, String level) { status = "ready"; contentEnc = content; clientSummaryEnc = summary; riskLevel = level; readyAt = Instant.now(); }
    public void fail() { status = "failed"; }
    public void regenerate() { status = "generating"; }
}
