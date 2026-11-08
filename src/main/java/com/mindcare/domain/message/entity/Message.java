package com.mindcare.domain.message.entity;

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
import org.hibernate.annotations.UuidGenerator;

@Entity @Table(name = "messages") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7) private UUID id;
    @Column(nullable = false) private UUID sessionId;
    private UUID senderId;
    @Column(nullable = false) private String kind;
    @Column(name = "body_enc", nullable = false) private byte[] bodyEnc;
    private String clientMsgId;
    @Column(nullable = false) private String riskLevel;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    @Builder private Message(UUID sessionId, UUID senderId, String kind, byte[] bodyEnc, String clientMsgId, String riskLevel) { this.sessionId=sessionId; this.senderId=senderId; this.kind=kind; this.bodyEnc=bodyEnc; this.clientMsgId=clientMsgId; this.riskLevel=riskLevel; }
}
