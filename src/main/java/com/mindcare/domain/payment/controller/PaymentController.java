package com.mindcare.domain.payment.controller;

import com.mindcare.domain.payment.dto.IdentityVerifyRequest;
import com.mindcare.domain.payment.dto.IdentityVerifyResponse;
import com.mindcare.domain.payment.dto.PaymentConfirmRequest;
import com.mindcare.domain.payment.dto.PaymentConfirmResponse;
import com.mindcare.domain.payment.dto.PaymentPrepareRequest;
import com.mindcare.domain.payment.dto.PaymentPrepareResponse;
import com.mindcare.domain.payment.dto.RefundRequest;
import com.mindcare.domain.payment.dto.RefundResponse;
import com.mindcare.domain.payment.service.IdentityService;
import com.mindcare.domain.payment.service.PaymentService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import com.mindcare.global.web.Idempotent;
import com.mindcare.global.web.IdempotencyInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final IdentityService identityService;
    private final PaymentService paymentService;

    @Operation(operationId = "PAY-01", summary = "결제용 본인인증 (mock). 응답에 개인정보 없음")
    @PostMapping("/identity/verify")
    public IdentityVerifyResponse verifyIdentity(@LoginUser AuthUser user, @Valid @RequestBody IdentityVerifyRequest request) {
        identityService.verify(user, request);
        return new IdentityVerifyResponse(true);
    }

    @Operation(operationId = "PAY-02", summary = "결제 준비. 본인인증 없으면 409, 홀드 만료 410")
    @Idempotent
    @PostMapping("/payments/prepare")
    public PaymentPrepareResponse prepare(@LoginUser AuthUser user, @Valid @RequestBody PaymentPrepareRequest request,
            @Parameter(required = true) @RequestHeader(IdempotencyInterceptor.HEADER) String idempotencyKey) {
        return paymentService.prepare(user, request.sessionId(), idempotencyKey);
    }

    @Operation(operationId = "PAY-03", summary = "결제 확정 (mock은 즉시 paid). 세션 확정·슬롯 예약 완료")
    @Idempotent
    @PostMapping("/payments/confirm")
    public PaymentConfirmResponse confirm(@LoginUser AuthUser user, @Valid @RequestBody PaymentConfirmRequest request,
            @Parameter(required = true) @RequestHeader(IdempotencyInterceptor.HEADER) String idempotencyKey) {
        return paymentService.confirm(user, request);
    }

    @Operation(operationId = "PAY-04", summary = "어드민 수동 환불 (amount 없으면 전액)")
    @Idempotent
    @PostMapping("/payments/{paymentId}/refund")
    public RefundResponse refund(@LoginUser AuthUser user, @PathVariable UUID paymentId,
            @Valid @RequestBody RefundRequest request,
            @Parameter(required = true) @RequestHeader(IdempotencyInterceptor.HEADER) String idempotencyKey) {
        return paymentService.refundByAdmin(user, paymentId, request.amount());
    }
}
