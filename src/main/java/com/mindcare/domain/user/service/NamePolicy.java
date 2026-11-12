package com.mindcare.domain.user.service;

import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TRD 12-6 입력 정규화·닉네임 규칙. 닉네임(내담자 공개 이름)과 활동명(상담사 공개 이름)에 쓴다.
 * 규칙에 안 맞으면 422 VALIDATION_ERROR, details.fields = [{field, reason}].
 */
public final class NamePolicy {

    private static final Pattern NICKNAME = Pattern.compile("^[가-힣A-Za-z0-9]+$");
    // 활동명은 "윤 선생님"처럼 띄어쓰기를 허용 (연속 공백은 하나로 줄임)
    private static final Pattern DISPLAY_NAME = Pattern.compile("^[가-힣A-Za-z0-9 ]+$");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    // 운영자 사칭·서비스명 사칭 방지용 최소 목록 (간지톤 범위)
    private static final List<String> FORBIDDEN = List.of(
            "관리자", "운영자", "운영진", "어드민", "admin", "마인드케어", "mindcare", "공식");

    private NamePolicy() {
    }

    /** 앞뒤 공백 제거 + NFC + 제어 문자 제거 (TRD 12-6 문자열 공통 규칙). */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
        return CONTROL.matcher(nfc).replaceAll("").strip();
    }

    /** 닉네임: 2~12자, 한글·영문·숫자만, 금칙어·연락처처럼 보이는 값 거부. 정규화한 값을 돌려준다. */
    public static String nickname(String value) {
        String normalized = normalize(value);
        check("nickname", normalized, 2, 12, NICKNAME);
        return normalized;
    }

    /** 활동명: 2~20자, 한글·영문·숫자·띄어쓰기. 나머지 규칙은 닉네임과 같다. */
    public static String displayName(String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            normalized = SPACES.matcher(normalized).replaceAll(" ");
        }
        check("display_name", normalized, 2, 20, DISPLAY_NAME);
        return normalized;
    }

    private static void check(String field, String value, int min, int max, Pattern allowed) {
        if (value == null) {
            throw invalid(field, "not_blank");
        }
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw invalid(field, "size");
        }
        if (!allowed.matcher(value).matches()) {
            throw invalid(field, "invalid_characters");
        }
        // 숫자 8자 이상이면 전화번호를 숨겨 넣은 것으로 본다 (@는 위 문자 규칙에서 이미 막힘)
        if (value.chars().filter(Character::isDigit).count() >= 8) {
            throw invalid(field, "looks_like_contact");
        }
        String lower = value.toLowerCase(Locale.ROOT).replace(" ", "");
        if (FORBIDDEN.stream().anyMatch(lower::contains)) {
            throw invalid(field, "forbidden_word");
        }
    }

    static AppException invalid(String field, String reason) {
        return new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
