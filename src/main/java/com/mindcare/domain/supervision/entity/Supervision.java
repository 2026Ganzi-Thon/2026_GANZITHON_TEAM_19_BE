package com.mindcare.domain.supervision.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.*;
@Entity @Table(name="supervisions") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Supervision {
 @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) private UUID id;
 @Column(nullable=false) private String kind; private UUID referralId; private UUID reportId; private UUID requestId; private UUID coachingSessionId;
 @Column(nullable=false) private UUID expertId; @Column(nullable=false) private UUID traineeId; private byte[] contentEnc;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private SupervisionStatus status=SupervisionStatus.PENDING;
 private Instant dueAt; private Instant submittedAt;
 @CreationTimestamp @Column(nullable=false,updatable=false) private Instant createdAt;
 public void submit(byte[] content){if(status!=SupervisionStatus.PENDING&&status!=SupervisionStatus.DRAFT)throw new IllegalStateException();contentEnc=content;status=SupervisionStatus.SUBMITTED;submittedAt=Instant.now();}
}
