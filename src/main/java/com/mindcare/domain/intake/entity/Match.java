package com.mindcare.domain.intake.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "matches") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Match {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "intake_id", nullable = false) private Intake intake;
    @Column(nullable = false) private UUID providerId;
    @Column(nullable = false) private String track;
    @Column(nullable = false) private BigDecimal score;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> reasons;
    @Column(nullable = false) private short rank;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
}
