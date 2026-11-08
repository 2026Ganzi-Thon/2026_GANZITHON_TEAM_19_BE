package com.mindcare.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalRiskDetectorTest {
    private final LocalRiskDetector detector = new LocalRiskDetector();

    @Test void imminent_expression_is_critical() {
        RiskAssessment result = detector.assess("오늘 죽을 방법을 알아보고 있어요");
        assertThat(result.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.ruleHits()).contains("imminent_suicide");
    }

    @Test void ordinary_text_is_not_flagged() {
        assertThat(detector.assess("요즘 진로 때문에 마음이 복잡해요").level()).isEqualTo(RiskLevel.NONE);
    }
}
