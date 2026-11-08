package com.mindcare.domain.job.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="jobs") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Job {
 @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) private UUID id;
 @Column(nullable=false) private String type;
 @Column(nullable=false) private String dedupKey;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private Map<String,String> payload;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private JobStatus status;
 @Column(nullable=false) private short attempts;
 @Column(nullable=false) private short maxAttempts;
 @Column(nullable=false) private Instant runAt;
 @CreationTimestamp @Column(nullable=false,updatable=false) private Instant createdAt;
 public Job(String type,String dedupKey,Map<String,String> payload){this.type=type;this.dedupKey=dedupKey;this.payload=Map.copyOf(payload);status=JobStatus.QUEUED;maxAttempts=3;runAt=Instant.now();}
}
