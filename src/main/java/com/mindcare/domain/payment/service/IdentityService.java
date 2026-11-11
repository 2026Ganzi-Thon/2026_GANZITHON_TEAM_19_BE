package com.mindcare.domain.payment.service;

import com.mindcare.domain.payment.dto.IdentityVerifyRequest;
import com.mindcare.domain.payment.entity.IdentityVerification;
import com.mindcare.domain.payment.repository.IdentityVerificationRepository;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.crypto.HmacService;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PAY-01 결제용 본인인증. 결과는 암호화해서 identity_verifications에만 두고, 응답에는 성공 여부만 준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentityService {

    private final IdentityVerificationRepository repository;
    private final IdentityProvider identityProvider;
    private final SensitiveTextCodec codec;
    private final HmacService hmacService;
    private final Clock clock;

    @Transactional
    public void verify(AuthUser user, IdentityVerifyRequest request) {
        user.requireRole(UserRole.CLIENT);
        String phone = request.normalizedPhone();
        if (!identityProvider.verify(request.name().trim(), request.birth(), phone)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, Map.of("reason", "identity_not_verified"));
        }
        byte[] phoneHmac = hmacService.hash(phone);
        repository.findByPhoneHmac(phoneHmac)
                .filter(existing -> !existing.getUserId().equals(user.id()))
                .ifPresent(existing -> {
                    // 한 휴대폰 번호는 한 계정에서만 본인인증
                    throw new AppException(ErrorCode.ALREADY_REGISTERED, Map.of("field", "phone"));
                });

        byte[] nameEnc = codec.encrypt(request.name().trim());
        byte[] birthEnc = codec.encrypt(request.birth().toString());
        byte[] phoneEnc = codec.encrypt(phone);
        repository.findById(user.id()).ifPresentOrElse(
                existing -> existing.update(nameEnc, birthEnc, phoneEnc, phoneHmac, identityProvider.name(), clock.instant()),
                () -> repository.saveAndFlush(IdentityVerification.of(user.id(), nameEnc, birthEnc, phoneEnc, phoneHmac,
                        identityProvider.name(), clock.instant())));
    }

    public boolean isVerified(UUID userId) {
        return repository.existsById(userId);
    }
}
