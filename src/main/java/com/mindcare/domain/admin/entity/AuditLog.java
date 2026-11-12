package com.mindcare.domain.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** TRD 11장 audit_logs. 민감정보 열람·권한 변경 기록 (추가만 하고 수정·삭제하지 않는다). */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String targetType;

    private UUID targetId;

    private String reason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static AuditLog of(UUID actorId, String action, String targetType, UUID targetId, String reason) {
        AuditLog log = new AuditLog();
        log.actorId = actorId;
        log.action = action;
        log.targetType = targetType;
        log.targetId = targetId;
        log.reason = reason;
        return log;
    }
}
