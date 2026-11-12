package com.mindcare.domain.notification.push;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Firebase 키가 있으면 FCM, 없으면(로컬·CI) 로그만 남긴다. 인증(FirebaseConfig)과 같은 기준. */
@Slf4j
@Configuration
public class PushConfig {

    @Bean
    PushSender pushSender(@Value("${app.firebase.credentials-path}") String credentialsPath) {
        if (StringUtils.hasText(credentialsPath)) {
            return new FcmPushSender();
        }
        return (tokens, title, body, data) -> {
            log.debug("push skipped (no Firebase key) tokens={}", tokens.size());
            return List.of();
        };
    }
}
