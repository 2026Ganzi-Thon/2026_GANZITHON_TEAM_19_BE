package com.mindcare.domain.payment.service;

import java.util.UUID;

/**
 * 결제 확정(PAY-03) 때 같은 트랜잭션 안에서 발행된다.
 * 세션 도메인이 받아서 세션을 pending_payment → confirmed로 바꾼다 (@EventListener, 같은 트랜잭션이라 실패하면 결제도 롤백).
 */
public record PaymentConfirmedEvent(UUID sessionId, UUID paymentId) {
}
