package com.mindcare.domain.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Network-independent first pass for intake and message Red Flag detection. */
@Component
public class LocalRiskDetector {
    public RiskAssessment assess(String text) {
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
}
