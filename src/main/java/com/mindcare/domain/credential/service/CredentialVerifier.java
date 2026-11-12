package com.mindcare.domain.credential.service;

import com.mindcare.domain.credential.entity.VerifyResult;
import java.time.LocalDate;
import java.util.Map;

/**
 * TRD 17장 자격 진위 확인. 간지톤은 MockCredentialVerifier, 실연동(Q-Net 등)은 P1.
 */
public interface CredentialVerifier {

    Result verify(String certName, String certNumber, String realName, LocalDate issuedOn);

    record Result(VerifyResult result, Map<String, Object> raw) {
    }
}
