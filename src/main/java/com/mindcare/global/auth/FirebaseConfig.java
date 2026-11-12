package com.mindcare.global.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * 키 경로가 있으면 실제 Firebase로 검증한다.
 * 없으면 local에서는 모든 토큰을 거절하는 구현으로 뜨고(키 없이도 개발·CI 가능), 그 외 환경에서는 서버가 뜨지 않는다.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Bean
    public TokenVerifier tokenVerifier(
            @Value("${app.firebase.credentials-path}") String credentialsPath, Environment environment)
            throws IOException {
        if (StringUtils.hasText(credentialsPath)) {
            return new FirebaseTokenVerifier(FirebaseAuth.getInstance(firebaseApp(credentialsPath)));
        }
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            log.warn("FIREBASE_CREDENTIALS_PATH가 없어서 모든 로그인 토큰을 거절합니다 (local)");
            return token -> {
                throw new AppException(ErrorCode.UNAUTHENTICATED);
            };
        }
        throw new IllegalStateException("FIREBASE_CREDENTIALS_PATH 환경변수가 필요합니다");
    }

    @Bean
    public TokenRevoker tokenRevoker(@Value("${app.firebase.credentials-path}") String credentialsPath)
            throws IOException {
        if (!StringUtils.hasText(credentialsPath)) {
            return uid -> log.debug("token revoke skipped (no Firebase key)");
        }
        FirebaseAuth auth = FirebaseAuth.getInstance(firebaseApp(credentialsPath));
        return uid -> {
            try {
                auth.revokeRefreshTokens(uid);
            } catch (FirebaseAuthException e) {
                // 커밋 뒤에 불리므로 실패해도 상태 변경은 유지된다. 정지·영구정지는 requireActive가 막는다
                log.error("Firebase revokeRefreshTokens failed: {}", e.getAuthErrorCode(), e);
            }
        };
    }

    @Bean
    public AuthAccounts authAccounts(@Value("${app.firebase.credentials-path}") String credentialsPath)
            throws IOException {
        if (!StringUtils.hasText(credentialsPath)) {
            return new AuthAccounts() {
                @Override
                public String ensure(String uid, String email, String password, String displayName) {
                    return uid;
                }

                @Override
                public boolean isReal() {
                    return false;
                }
            };
        }
        return new FirebaseAuthAccounts(FirebaseAuth.getInstance(firebaseApp(credentialsPath)));
    }

    private FirebaseApp firebaseApp(String credentialsPath) throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        try (InputStream credentials = Files.newInputStream(Path.of(credentialsPath))) {
            return FirebaseApp.initializeApp(FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentials))
                    .build());
        }
    }
}
