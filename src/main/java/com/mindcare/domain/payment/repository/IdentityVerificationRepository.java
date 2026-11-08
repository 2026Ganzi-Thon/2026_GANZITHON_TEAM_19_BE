package com.mindcare.domain.payment.repository;

import com.mindcare.domain.payment.entity.IdentityVerification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, UUID> {

    Optional<IdentityVerification> findByPhoneHmac(byte[] phoneHmac);
}
