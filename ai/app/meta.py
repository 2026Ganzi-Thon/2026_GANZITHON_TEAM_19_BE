"""응답 meta 조립 (TRD 16-1). 백엔드는 model·prompt_version을 reports 행에 저장한다."""

import time
from typing import TYPE_CHECKING

from app.schemas import Meta

if TYPE_CHECKING:
    from app.services import Services


def build_meta(
    services: "Services",
    prompt_version: str,
    started: float,
    *,
    model: str | None = None,
    tokens_in: int = 0,
    tokens_out: int = 0,
    fallback: bool = False,
    safety_filtered: bool = False,
) -> Meta:
    return Meta(
        provider=services.llm.provider,
        model=model or services.llm.model,
        prompt_version=prompt_version,
        tokens_in=tokens_in,
        tokens_out=tokens_out,
        latency_ms=int((time.perf_counter() - started) * 1000),
        fallback=fallback,
        cache_hit=False,
        safety_filtered=safety_filtered,
    )
