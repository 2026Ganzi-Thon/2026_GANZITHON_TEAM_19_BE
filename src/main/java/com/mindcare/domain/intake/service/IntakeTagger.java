package com.mindcare.domain.intake.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AI-01 인테이크 태깅을 내부 AI 서버에 요청한다.
 * AI 서버가 없거나 실패하면 빈 값을 돌려주고, 호출한 쪽은 fallback으로 저장해 인테이크 접수를 막지 않는다.
 */
@Slf4j
@Component
public class IntakeTagger {
    static final int MAX_TEXT_CHARS = 2000;
    private static final Map<String, Integer> LIMITS = Map.of("emotions", 5, "distortions", 3, "keywords", 8);

    private final RestClient aiClient;

    @Autowired
    public IntakeTagger(@Value("${AI_BASE_URL:}") String aiBaseUrl,
            @Value("${AI_INTERNAL_TOKEN:}") String internalToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.aiClient = aiBaseUrl == null || aiBaseUrl.isBlank() ? null : RestClient.builder()
                .baseUrl(aiBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken == null ? "" : internalToken)
                .requestFactory(requestFactory)
                .build();
    }

    /** 성공하면 emotions·distortions·keywords 세 키를 가진 태그, 실패하면 빈 Optional. */
    public Optional<Map<String, List<String>>> tag(String category, String text) {
        if (aiClient == null || text == null || text.isBlank()) return Optional.empty();
        try {
            TagResponse response = aiClient.post().uri("/v1/intake/tags")
                    .body(new TagRequest(category, text.substring(0, Math.min(text.length(), MAX_TEXT_CHARS))))
                    .retrieve().body(TagResponse.class);
            if (response == null) return Optional.empty();
            return Optional.of(normalize(response));
        } catch (RestClientException e) {
            log.warn("intake tagging failed, falling back: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static Map<String, List<String>> normalize(TagResponse response) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("emotions", clean(response.emotions(), LIMITS.get("emotions")));
        tags.put("distortions", clean(response.distortions(), LIMITS.get("distortions")));
        tags.put("keywords", clean(response.keywords(), LIMITS.get("keywords")));
        return tags;
    }

    private static List<String> clean(List<String> values, int limit) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().limit(limit).toList();
    }

    private record TagRequest(String category, String text) { }

    record TagResponse(List<String> emotions, List<String> distortions, List<String> keywords) { }
}
