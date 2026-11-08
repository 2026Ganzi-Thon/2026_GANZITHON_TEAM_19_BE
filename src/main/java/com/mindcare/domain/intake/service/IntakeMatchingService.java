package com.mindcare.domain.intake.service;

import com.mindcare.domain.intake.entity.Intake;
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
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class IntakeMatchingService {
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
                : Arrays.stream(candidate.getSpecialties()).map(s -> s.toLowerCase(Locale.ROOT)).toList();
        String category = intake.getCategory().getValue();
        double categoryScore = specialties.contains(category) ? 1 : 0;
        double keywordScore = intake.getAiTags().getOrDefault("keywords", List.of()).stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(specialties::contains) ? 1 : categoryScore;
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
}
