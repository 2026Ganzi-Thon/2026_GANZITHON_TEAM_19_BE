package com.mindcare.domain.auth.service;

import com.mindcare.domain.auth.dto.TermsResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * AUTH-02 가입 동의 문서 (TRD H-05, 부록 E-1). 본문은 resources/terms/{type}.md.
 * 문구를 바꾸면 version을 올린다 → 이전 버전으로 동의한 가입 요청은 422 (프론트가 새 본문을 다시 보여주게).
 * 서버가 뜰 때 한 번 읽어서 들고 있는다 (파일이 없으면 서버가 안 뜬다).
 */
@Component
public class TermsCatalog {

    private record Entry(String type, String version, String title, boolean required) {
    }

    // 디자인 명세서 A-03: 필수 4 + 선택 2, 이 순서로 체크박스를 보여준다
    private static final List<Entry> ENTRIES = List.of(
            new Entry("terms", "1.0", "이용약관", true),
            new Entry("privacy", "1.0", "개인정보 수집·이용 동의", true),
            new Entry("sensitive", "1.0", "민감정보(정신건강 정보) 처리 동의", true),
            new Entry("age14", "1.0", "만 14세 이상 확인", true),
            new Entry("training_data", "1.0", "수련 목적 비식별 활용 동의", false),
            new Entry("marketing", "1.0", "마케팅 정보 수신 동의", false));

    private final List<TermsResponse> terms;

    public TermsCatalog() {
        List<TermsResponse> loaded = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            loaded.add(new TermsResponse(entry.type(), entry.version(), entry.title(), read(entry.type()),
                    entry.required()));
        }
        this.terms = List.copyOf(loaded);
    }

    public List<TermsResponse> all() {
        return terms;
    }

    /** 지금 유효한 버전. 모르는 type이면 빈 값. */
    public Optional<String> currentVersion(String type) {
        return ENTRIES.stream().filter(e -> e.type().equals(type)).map(Entry::version).findFirst();
    }

    private static String read(String type) {
        try (InputStream in = new ClassPathResource("terms/" + type + ".md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("terms/" + type + ".md 를 읽을 수 없습니다", e);
        }
    }
}
