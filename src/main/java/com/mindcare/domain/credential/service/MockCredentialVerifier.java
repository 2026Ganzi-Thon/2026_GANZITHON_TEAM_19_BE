package com.mindcare.domain.credential.service;

import com.mindcare.domain.credential.entity.VerifyResult;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * TRD 17장 MockVerifier 규칙: 0000으로 끝나면 mismatch, 9999로 끝나면 unavailable, 나머지는 match.
 * (TRD의 "시드에 등록된 번호만 match"는 시연 데이터가 생기면 맞춘다)
 */
@Component
public class MockCredentialVerifier implements CredentialVerifier {

    @Override
    public Result verify(String certName, String certNumber, String realName, LocalDate issuedOn) {
        VerifyResult result = certNumber.endsWith("0000") ? VerifyResult.MISMATCH
                : certNumber.endsWith("9999") ? VerifyResult.UNAVAILABLE
                : VerifyResult.MATCH;
        return new Result(result, Map.of("provider", "mock", "result", result.getValue()));
    }
}
