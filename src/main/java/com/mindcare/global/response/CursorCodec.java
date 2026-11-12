package com.mindcare.global.response;

import com.mindcare.global.crypto.HmacService;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * TRD 12-6 목록 커서: 서버가 서명한 불투명 문자열. 프론트는 next_cursor를 그대로 다시 보내기만 한다.
 * 형식: base64url(값) + "." + base64url(HMAC 앞 16바이트). 서명이 안 맞으면(위조·손상) 400 BAD_REQUEST.
 *
 * <pre>
 * String next = cursorCodec.encode(lastItem.getId().toString());
 * UUID after = UUID.fromString(cursorCodec.decode(cursor));
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int SIGNATURE_BYTES = 16;

    private final HmacService hmacService;

    public String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "." + ENCODER.encodeToString(sign(value));
    }

    public String decode(String cursor) {
        try {
            int dot = cursor.indexOf('.');
            if (dot <= 0) {
                throw invalid();
            }
            String value = new String(DECODER.decode(cursor.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = DECODER.decode(cursor.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, sign(value))) {
                throw invalid();
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    // 다른 HMAC 용도(휴대폰 해시 등)와 섞이지 않게 접두어를 붙여 서명한다
    private byte[] sign(String value) {
        return Arrays.copyOf(hmacService.hash("cursor:" + value), SIGNATURE_BYTES);
    }

    private static AppException invalid() {
        return new AppException(ErrorCode.BAD_REQUEST, "목록 커서가 올바르지 않습니다", Map.of("field", "cursor"));
    }
}
