package com.mindcare.domain.payment.service;

import java.time.LocalDate;

/**
 * 본인인증 기관 연동. 실제 연동(PASS 등)은 기관 계약이 필요해서 간지톤에서는 mock만 쓴다 (TRD 1-1).
 */
public interface IdentityProvider {

    String name();

    boolean verify(String name, LocalDate birth, String phone);
}
