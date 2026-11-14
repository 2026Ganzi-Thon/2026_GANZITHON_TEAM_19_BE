package com.mindcare.domain.risk;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Risk detection uses the AI service when configured, while retaining the local rules as a safety
 * floor and an outage fallback. A remote result can raise a risk level but can never lower one
 * already found by the deterministic rules.
 */
@Component
public class LocalRiskDetector {
    private final RestClient aiClient;

    /** Keeps the detector usable in small unit tests and environments without an AI endpoint. */
    public LocalRiskDetector() {
        this.aiClient = null;
    }

    @Autowired
    public LocalRiskDetector(@Value("${AI_BASE_URL:}") String aiBaseUrl,
            @Value("${AI_INTERNAL_TOKEN:}") String internalToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.aiClient = aiBaseUrl == null || aiBaseUrl.isBlank() ? null : RestClient.builder()
                .baseUrl(aiBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken == null ? "" : internalToken)
                .requestFactory(requestFactory)
                .build();
    }

    public RiskAssessment assess(String text) {
        RiskAssessment local = assessLocally(text);
        if (aiClient == null || text == null || text.isBlank()) return local;
        try {
            RiskResponse remote = aiClient.post().uri("/v1/risk/assess")
                    .body(new RiskRequest(text.substring(0, Math.min(text.length(), 4000)), List.of(), true))
                    .retrieve().body(RiskResponse.class);
            if (remote == null || remote.level() == null) return local;
            RiskLevel remoteLevel = RiskLevel.valueOf(remote.level().toUpperCase(Locale.ROOT));
            Set<String> hits = new LinkedHashSet<>(local.ruleHits());
            if (remote.rule_hits() != null) hits.addAll(remote.rule_hits());
            return new RiskAssessment(remoteLevel.ordinal() > local.level().ordinal() ? remoteLevel : local.level(),
                    List.copyOf(hits));
        } catch (RestClientException | IllegalArgumentException ignored) {
            // Risk alerts must remain available even when the internal AI service is unavailable.
            return local;
        }
    }

    private RiskAssessment assessLocally(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> hits = new ArrayList<>();
        if (contains(normalized, "자살", "목숨을 끊", "죽고 싶", "죽어야")) hits.add("suicidal_ideation");
        if (contains(normalized, "지금 죽", "오늘 죽", "유서", "방법을 알아")) hits.add("imminent_suicide");
        if (contains(normalized, "자해", "손목을 긋", "나를 해치")) hits.add("self_harm");
        if (contains(normalized, "죽여", "해치고 싶", "قتل")) hits.add("harm_to_others");
        RiskLevel level = hits.contains("imminent_suicide") ? RiskLevel.CRITICAL
                : hits.contains("suicidal_ideation") ? RiskLevel.HIGH
                : hits.contains("self_harm") || hits.contains("harm_to_others") ? RiskLevel.MEDIUM : RiskLevel.NONE;
        return new RiskAssessment(level, List.copyOf(hits));
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private record RiskRequest(String text, List<String> context, boolean use_llm) { }

    private record RiskResponse(String level, List<String> rule_hits) { }
}
