package com.mindcare.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각은 Clock 빈으로 받는다. 테스트에서 시간을 고정하거나 앞당길 수 있게 하려는 것 (홀드 만료 등).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
