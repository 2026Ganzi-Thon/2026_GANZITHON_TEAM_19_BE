package com.mindcare.domain.complaint.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.*;
@Entity @Table(name="complaints") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Complaint {
 @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) private UUID id;
 @Column(nullable=false) private UUID sessionId; private UUID reporterId; @Column(nullable=false) private UUID targetId;
 @Column(nullable=false) private String source; @Column(nullable=false) private String category; private byte[] descriptionEnc; private byte[] maskedTranscriptEnc;
 @Column(nullable=false) private String status="received"; private String decision; private String decisionReason; private UUID decidedBy; private Instant decidedAt;
 @CreationTimestamp @Column(nullable=false,updatable=false) private Instant createdAt;
}
