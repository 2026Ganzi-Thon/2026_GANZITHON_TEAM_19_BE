package com.mindcare.domain.certificate.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.*;
@Entity @Table(name="certificates") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Certificate {
 @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) private UUID id;
 @Column(nullable=false) private UUID traineeId;
 @Column(nullable=false,unique=true) private String code;
 @Column(nullable=false) private String status="generating";
 private String fileKey; private Double hours; private Integer sessionCount; private Integer supervisionCount; private Instant issuedAt;
 @CreationTimestamp @Column(nullable=false,updatable=false) private Instant createdAt;
}
