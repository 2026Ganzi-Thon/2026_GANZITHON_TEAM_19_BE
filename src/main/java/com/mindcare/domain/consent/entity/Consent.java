package com.mindcare.domain.consent.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;
@Entity @Table(name="consents") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Consent {
 @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) private UUID id;
 @Column(nullable=false) private UUID userId;
 @Column(nullable=false) private String type;
 @Column(nullable=false) private String version;
 private UUID targetExpertId; private UUID reportId; private Integer reportVersion; private UUID referralId;
 @Column(nullable=false) private Instant grantedAt; private Instant revokedAt; private String revokedReason;
 @CreationTimestamp @Column(nullable=false,updatable=false) private Instant createdAt;
 public Consent(UUID userId,String type,String version,UUID targetExpertId,UUID reportId,Integer reportVersion,UUID referralId){this.userId=userId;this.type=type;this.version=version;this.targetExpertId=targetExpertId;this.reportId=reportId;this.reportVersion=reportVersion;this.referralId=referralId;this.grantedAt=Instant.now();}
 public void revoke(String reason){if(revokedAt!=null) throw new IllegalStateException(); revokedAt=Instant.now();revokedReason=reason;}
}
