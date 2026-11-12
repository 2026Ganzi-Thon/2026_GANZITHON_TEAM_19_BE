package com.mindcare.domain.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * TRD 11장 settlements. 상담사에게 줄 돈 한 건. 금액은 원 단위 정수 (gross = fee + net).
 */
@Entity
@Table(name = "settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID providerId;

    @Column(nullable = false, updatable = false)
    private SettlementKind kind;

    @Column(updatable = false)
    private UUID sessionId;

    @Column(updatable = false)
    private UUID supervisionId;

    @Column(nullable = false)
    private int gross;

    @Column(nullable = false)
    private int fee;

    @Column(nullable = false)
    private int net;

    @Column(nullable = false)
    private SettlementStatus status;

    private String statusReason;

    private Instant paidAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static Settlement forSession(UUID providerId, UUID sessionId, int gross, int fee, boolean locked) {
        Settlement s = base(providerId, SettlementKind.SESSION, gross, fee);
        s.sessionId = sessionId;
        s.status = locked ? SettlementStatus.LOCKED : SettlementStatus.PENDING;
        s.statusReason = locked ? "supervision_pending" : null;
        return s;
    }

    public static Settlement forSupervision(UUID expertId, UUID supervisionId, int gross) {
        Settlement s = base(expertId, SettlementKind.SUPERVISION, gross, 0);
        s.supervisionId = supervisionId;
        s.status = SettlementStatus.PENDING;
        return s;
    }

    private static Settlement base(UUID providerId, SettlementKind kind, int gross, int fee) {
        Settlement s = new Settlement();
        s.providerId = providerId;
        s.kind = kind;
        s.gross = gross;
        s.fee = fee;
        s.net = gross - fee;
        return s;
    }

    /** 연계 슈퍼비전이 모두 끝나면 locked → pending. 다른 상태면 그대로. */
    public boolean unlock() {
        if (status != SettlementStatus.LOCKED) {
            return false;
        }
        status = SettlementStatus.PENDING;
        statusReason = null;
        return true;
    }

    public void markPaid(Instant now) {
        if (status != SettlementStatus.PENDING) {
            throw new IllegalStateException("Only pending settlements can be paid");
        }
        status = SettlementStatus.PAID;
        paidAt = now;
    }
}
