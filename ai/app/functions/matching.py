"""AI-02 매칭 점수. 인테이크 키워드·카테고리와 분야 태그 임베딩의 코사인 유사도 최댓값.

임베딩 호출이 실패하면 오류를 돌려준다. 그때의 폴백(태그 자카드 유사도)은
백엔드가 계산한다 (TRD 16-1).
"""

import time
from typing import TYPE_CHECKING

from app.meta import build_meta
from app.schemas import SpecialtyScoresRequest, SpecialtyScoresResponse

if TYPE_CHECKING:
    from app.services import Services

MATCH_TIMEOUT_SECONDS = 3.0
MATCH_VERSION = "score_specialties.v1"
SCORE_DIGITS = 4


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(y * y for y in b) ** 0.5
    if not norm_a or not norm_b:
        return 0.0
    return min(max(dot / (norm_a * norm_b), 0.0), 1.0)


async def score_specialties(
    req: SpecialtyScoresRequest, services: "Services"
) -> SpecialtyScoresResponse:
    started = time.perf_counter()
    label = services.dictionary.category_label(req.category)
    # 키워드와 태그는 백엔드가 넘긴 글이다. 외부 임베딩 API로 나가기 전에 마스킹한다.
    query = services.masker.mask(" ".join([*req.keywords, label]))
    # 빈 태그는 임베딩 API가 받지 않으므로 뺀다.
    tags = sorted({tag for candidate in req.candidates for tag in candidate.tags if tag.strip()})

    cache = services.tag_vectors  # 분야 태그 임베딩 캐시 (프로세스 메모리)
    missing = [tag for tag in tags if tag not in cache]
    texts = [query, *(services.masker.mask(tag) for tag in missing)]
    vectors = await services.gateway.embed(texts, timeout=MATCH_TIMEOUT_SECONDS)
    query_vector = vectors[0]
    fresh = dict(zip(missing, vectors[1:], strict=True))
    # 사전에 있는 분야 태그만 캐시한다. 임의의 문자열까지 담으면 메모리가 끝없이 는다.
    known = set(services.dictionary.specialties)
    cache.update({tag: vector for tag, vector in fresh.items() if tag in known})
    tag_vectors = {tag: cache[tag] if tag in cache else fresh[tag] for tag in tags}

    exact = {req.category.lower(), label.lower(), *(k.lower() for k in req.keywords)}
    scores: dict[str, float] = {}
    for candidate in req.candidates:
        best = 0.0
        for tag in candidate.tags:
            if tag.lower() in exact:
                best = 1.0
                break
            if tag in tag_vectors:
                best = max(best, cosine(query_vector, tag_vectors[tag]))
        scores[candidate.id] = round(best, SCORE_DIGITS)
    return SpecialtyScoresResponse(scores=scores, meta=build_meta(services, MATCH_VERSION, started))
