"""AI-01 인테이크 태깅. 감정·인지왜곡 태그는 고정 사전 값만 허용한다 (TRD 16-2)."""

import time
from typing import TYPE_CHECKING

from app.functions.drafts import TagDraft
from app.meta import build_meta
from app.schemas import IntakeTagsResponse, TagIntakeRequest

if TYPE_CHECKING:
    from app.services import Services

TAG_TIMEOUT_SECONDS = 5.0
TAG_MAX_TOKENS = 300
MAX_EMOTIONS = 5
MAX_DISTORTIONS = 3
MAX_KEYWORDS = 8
MAX_KEYWORD_CHARS = 20


def _only_allowed(values: list[str], allowed: list[str], limit: int) -> list[str]:
    seen: list[str] = []
    for value in (v.strip() for v in values):
        if value in allowed and value not in seen:
            seen.append(value)
    return seen[:limit]


def _clean_keywords(values: list[str], limit: int) -> list[str]:
    seen: list[str] = []
    for value in (v.strip() for v in values):
        if value and len(value) <= MAX_KEYWORD_CHARS and value not in seen:
            seen.append(value)
    return seen[:limit]


async def tag_intake(req: TagIntakeRequest, services: "Services") -> IntakeTagsResponse:
    started = time.perf_counter()
    prompt = services.prompts["tag_intake"]
    dictionary = services.dictionary
    masked = services.masker.mask(req.text)
    # 카테고리도 백엔드가 넘긴 글이므로 마스킹을 거쳐 보낸다.
    label = services.masker.mask(dictionary.category_label(req.category))
    user = f"카테고리: {label}\n<user_text>\n{masked}\n</user_text>"
    result = await services.gateway.complete(
        system=prompt.text.replace("{emotions}", ", ".join(dictionary.emotions)).replace(
            "{distortions}", ", ".join(dictionary.distortions)
        ),
        user=user,
        schema=TagDraft,
        timeout=TAG_TIMEOUT_SECONDS,
        max_tokens=TAG_MAX_TOKENS,
    )
    draft = result.parsed
    return IntakeTagsResponse(
        emotions=_only_allowed(draft.emotions, dictionary.emotions, MAX_EMOTIONS),
        distortions=_only_allowed(draft.distortions, dictionary.distortions, MAX_DISTORTIONS),
        keywords=_clean_keywords(draft.keywords, MAX_KEYWORDS),
        meta=build_meta(
            services,
            prompt.version,
            started,
            model=result.model,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
        ),
    )
