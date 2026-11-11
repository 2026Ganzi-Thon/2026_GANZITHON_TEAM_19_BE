package com.mindcare.global.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 검색이 필요한 민감 값의 HMAC-SHA256 해시 (TRD 6-3: 휴대폰 중복 가입 확인 등).
 * 암호문은 매번 달라서 같은 값인지 비교할 수 없으므로, 비교용 해시 컬럼(_hmac)을 따로 둔다.
 */
@Component
public class HmacService {

    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public HmacService(@Value("${app.crypto.hmac-key}") String encodedKey) {
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (decoded.length < 32) {
            throw new IllegalArgumentException("app.crypto.hmac-key must be a base64 key of at least 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, ALGORITHM);
    }

    public byte[] hash(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute HMAC", e);
        }
    }
}
