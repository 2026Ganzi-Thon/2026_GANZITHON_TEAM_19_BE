package com.mindcare.domain.payment.service;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** 항상 인증 성공. 실제 기관 연동 전까지 사용한다. */
@Component
public class MockIdentityProvider implements IdentityProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean verify(String name, LocalDate birth, String phone) {
        return true;
    }
}
