package com.mindcare.domain.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindcare.domain.intake.entity.IntakeCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeMatchingServiceTest {

    @Test
    void korean_burnout_specialty_matches_burnout_category_code() {
        assertThat(IntakeMatchingService.matchesCategory(IntakeCategory.BURNOUT, List.of("직장 번아웃"))).isTrue();
    }

    @Test
    void unrelated_specialty_does_not_match_category() {
        assertThat(IntakeMatchingService.matchesCategory(IntakeCategory.BURNOUT, List.of("가족 관계"))).isFalse();
    }
}
