package com.mindcare.domain.session.service;

import com.mindcare.domain.payment.service.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 결제 확정(PAY-03)을 받아 세션을 confirmed로 바꾼다 (TRD 15-1).
 * 결제와 같은 트랜잭션에서 동기로 돈다. 여기서 실패하면 결제도 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class SessionPaymentListener {

    private final SessionService sessionService;

    @EventListener
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        sessionService.confirmPaid(event.sessionId());
    }
}
