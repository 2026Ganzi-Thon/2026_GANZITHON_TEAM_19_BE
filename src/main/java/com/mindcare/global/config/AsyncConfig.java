package com.mindcare.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @Async 사용 (FCM 푸시처럼 요청을 기다리게 할 필요 없는 부가 작업).
 * 실행기는 Spring Boot 기본 applicationTaskExecutor (스레드 풀).
 * 실패해도 다시 해야 하는 작업(리포트 생성 등)은 @Async 말고 jobs 큐(TRD 15-11)를 쓴다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
