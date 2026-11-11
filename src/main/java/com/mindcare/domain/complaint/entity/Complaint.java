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
 @Builder public Complaint(UUID sessionId,UUID reporterId,UUID targetId,String source,String category,byte[] descriptionEnc){this.sessionId=sessionId;this.reporterId=reporterId;this.targetId=targetId;this.source=source;this.category=category;this.descriptionEnc=descriptionEnc;}
 public void beginReview(){if(!"received".equals(status))throw new IllegalStateException();status="in_review";}
 public void decide(String value,String reason,UUID adminId){if(!"in_review".equals(status))throw new IllegalStateException();decision=value;decisionReason=reason;decidedBy=adminId;decidedAt=Instant.now();status="decided";}
}
