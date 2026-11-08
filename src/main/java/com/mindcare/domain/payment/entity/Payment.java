package com.mindcare.domain.payment.entity;

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
 * TRD 11-4 payments. 세션 하나에 결제 하나 (session_id unique).
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    public static final String PROVIDER_MOCK = "mock";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false)
    private UUID clientId;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false, unique = true, updatable = false)
    private String orderId;

    private String pgPaymentKey;

    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    private Instant paidAt;

    private Instant refundedAt;

    private Integer refundAmount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static Payment ready(UUID sessionId, UUID clientId, int amount, String idempotencyKey) {
        Payment payment = new Payment();
        payment.sessionId = sessionId;
        payment.clientId = clientId;
        payment.amount = amount;
        payment.provider = PROVIDER_MOCK;
        payment.orderId = "mc_" + UUID.randomUUID().toString().replace("-", "");
        payment.status = PaymentStatus.READY;
        payment.idempotencyKey = idempotencyKey;
        return payment;
    }

    public void markPaid(String pgPaymentKey, Instant now) {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("Only ready payments can be paid: " + status);
        }
        this.status = PaymentStatus.PAID;
        this.pgPaymentKey = pgPaymentKey;
        this.paidAt = now;
    }

    public void markRefunded(int refundAmount, Instant now) {
        if (status != PaymentStatus.PAID) {
            throw new IllegalStateException("Only paid payments can be refunded: " + status);
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundAmount = refundAmount;
        this.refundedAt = now;
    }
}
