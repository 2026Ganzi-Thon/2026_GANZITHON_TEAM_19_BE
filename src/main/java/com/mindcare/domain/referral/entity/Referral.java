package com.mindcare.domain.referral.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.*;
@Entity @Table(name="referrals") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Referral {
 @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) private UUID id;
 @Column(nullable=false) private UUID clientId; @Column(nullable=false) private UUID sourceSessionId; private UUID reportId;
 @Column(nullable=false) private String initiatedBy; private String traineeReason; private UUID expertId; private String status="suggested"; private String declineReason;
 @CreationTimestamp @Column(nullable=false,updatable=false) private Instant createdAt; @UpdateTimestamp @Column(nullable=false) private Instant updatedAt;
 public Referral(UUID clientId,UUID sourceSessionId,String initiatedBy,String traineeReason){this.clientId=clientId;this.sourceSessionId=sourceSessionId;this.initiatedBy=initiatedBy;this.traineeReason=traineeReason;}
 public void selectExpert(UUID id){if(!"suggested".equals(status)&&!"expert_selected".equals(status))throw new IllegalStateException();expertId=id;status="expert_selected";}
 public void cancel(String reason){if("accepted".equals(status)||"cancelled".equals(status))throw new IllegalStateException();status="cancelled";declineReason=reason;}
}
