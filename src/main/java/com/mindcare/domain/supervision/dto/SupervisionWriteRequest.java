package com.mindcare.domain.supervision.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 5대 항목 슈퍼비전 작성 (E08). draft=true면 임시 저장(검증 완화), false면 제출.
 * risk_rating: appropriate / underestimated / overestimated.
 */
public record SupervisionWriteRequest(@NotNull Boolean draft, @Size(max = 2000) String wellDone, @Size(max = 2000) String blindSpot,
        @Valid @Size(max = 2) List<Alternative> alternativeResponses, String riskRating, @Size(max = 1000) String riskComment,
        @Size(max = 500) String nextAction, @Valid Scores scores) {
    public record Alternative(UUID sessionId, @Size(max = 4000) String quote, @Size(max = 500) String suggestion) { }
    public record Scores(@Min(1) @Max(5) Integer rapport, @Min(1) @Max(5) Integer empathy, @Min(1) @Max(5) Integer risk,
            @Min(1) @Max(5) Integer structure) { }
}
