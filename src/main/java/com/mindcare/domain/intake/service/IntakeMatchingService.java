package com.mindcare.domain.intake.service;

import com.mindcare.domain.intake.entity.Intake;
import com.mindcare.domain.intake.entity.IntakeCategory;
import com.mindcare.domain.intake.entity.Match;
import com.mindcare.domain.intake.entity.RiskLevel;
import com.mindcare.domain.intake.repository.MatchingCandidateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class IntakeMatchingService {
    /** Provider specialties are user-facing Korean labels, whereas IntakeCategory is an API code. */
    private static final Map<IntakeCategory, Set<String>> CATEGORY_SPECIALTIES = categorySpecialties();
    private final MatchingCandidateRepository candidates;
    private final Clock clock = Clock.systemUTC();

    List<Match> createMatches(Intake intake) {
        String requiredTrack = intake.getRiskLevel().ordinal() >= RiskLevel.HIGH.ordinal() ? "expert" : "trainee";
        Instant now = clock.instant();
        return candidates.findEligible(requiredTrack, now.plus(Duration.ofDays(14)), intake.getClient().getId()).stream()
                .map(candidate -> scored(intake, candidate, now))
                .sorted(Comparator.comparing(Scored::score).reversed())
                .limit(3)
                .map(new java.util.function.Function<Scored, Match>() {
                    short rank = 0;
                    @Override public Match apply(Scored candidate) {
                        return Match.create(intake, candidate.candidate().getProviderId(),
                                "expert".equals(candidate.candidate().getProviderTrack()) ? "professional" : "coaching",
                                candidate.score(), candidate.reasons(), ++rank);
                    }
                }).toList();
    }

    private Scored scored(Intake intake, MatchingCandidateRepository.Candidate candidate, Instant now) {
        List<String> specialties = candidate.getSpecialties() == null ? List.of()
                : Arrays.stream(candidate.getSpecialties()).map(IntakeMatchingService::normalize).toList();
        String category = intake.getCategory().getValue();
        double categoryScore = matchesCategory(intake.getCategory(), specialties) ? 1 : 0;
        double keywordScore = intake.getAiTags().getOrDefault("keywords", List.of()).stream()
                .map(IntakeMatchingService::normalize).anyMatch(specialties::contains) ? 1 : categoryScore;
        double timeScore = candidate.getEarliestSlot().isBefore(now.plus(Duration.ofHours(72))) ? 1
                : candidate.getEarliestSlot().isBefore(now.plus(Duration.ofDays(7))) ? .5 : .2;
        double ratingScore = candidate.getRatingCount() == null || candidate.getRatingCount() < 3 ? .6
                : Math.max(0, Math.min(1, (candidate.getRatingAvg() - 1) / 4));
        BigDecimal score = BigDecimal.valueOf(.5 * keywordScore + .15 * categoryScore + .15 * timeScore
                + .1 * ratingScore + .1).setScale(4, RoundingMode.HALF_UP);
        List<String> reasons = new ArrayList<>();
        if (keywordScore > 0) reasons.add(category + " 분야와 잘 맞아요");
        if (timeScore >= .5) reasons.add("가까운 시간에 예약할 수 있어요");
        if (reasons.isEmpty()) reasons.add("상담 가능 시간이 있어요");
        return new Scored(candidate, score, reasons.stream().limit(2).toList());
    }

    private record Scored(MatchingCandidateRepository.Candidate candidate, BigDecimal score, List<String> reasons) { }

    static boolean matchesCategory(IntakeCategory category, List<String> specialties) {
        Set<String> labels = CATEGORY_SPECIALTIES.get(category);
        return labels != null && specialties.stream().anyMatch(labels::contains);
    }

    private static Map<IntakeCategory, Set<String>> categorySpecialties() {
        Map<IntakeCategory, Set<String>> labels = new EnumMap<>(IntakeCategory.class);
        labels.put(IntakeCategory.CAREER,
                normalizedSet("청년 진로", "진로", "취업 스트레스", "커리어"));
        labels.put(IntakeCategory.BURNOUT,
                normalizedSet("직장 번아웃", "번아웃", "직장 스트레스", "업무 스트레스"));
        labels.put(IntakeCategory.RELATIONSHIP,
                normalizedSet("대인관계 불안", "대인관계", "인간관계"));
        labels.put(IntakeCategory.ROMANCE,
                normalizedSet("연애 갈등", "연애", "이별"));
        labels.put(IntakeCategory.FAMILY,
                normalizedSet("가족 관계", "가족", "부모"));
        labels.put(IntakeCategory.SELF,
                normalizedSet("자존감", "자기이해", "정체성"));
        labels.put(IntakeCategory.OTHER, normalizedSet("기타"));
        return Map.copyOf(labels);
    }

    private static Set<String> normalizedSet(String... labels) {
        return Arrays.stream(labels).map(IntakeMatchingService::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
