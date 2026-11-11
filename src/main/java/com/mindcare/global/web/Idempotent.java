package com.mindcare.global.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TRD 12-1 멱등성: 이 API는 Idempotency-Key 헤더가 필수이고, 같은 사용자가 같은 키로 다시 요청하면
 * 첫 응답(상태 코드·본문)을 그대로 돌려준다 (Redis 24시간). 대상: SES-01, PAY-02, PAY-03, PAY-04.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
