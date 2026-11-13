"""요청 처리에 필요한 의존성 묶음. 기동할 때 한 번 만든다."""

from dataclasses import dataclass, field
from typing import Any

import redis.asyncio as redis_async

from app.config import Settings
from app.demo_cache.store import DemoCache
from app.functions.masking import PiiMasker
from app.functions.risk import RiskRuleEngine
from app.llm.breaker import CircuitBreaker
from app.llm.budget import MemoryTokenBudget, RedisTokenBudget, TokenBudget
from app.llm.client import LLMClient, get_llm
from app.llm.gateway import LLMGateway
from app.llm.limits import ConcurrencyLimits
from app.resources import (
    Prompt,
    TagDictionary,
    load_dictionary,
    load_pii_patterns,
    load_prompts,
    load_risk_lexicon,
)


@dataclass(frozen=True)
class Services:
    settings: Settings
    llm: LLMClient
    gateway: LLMGateway
    budget: TokenBudget
    dictionary: TagDictionary
    prompts: dict[str, Prompt]
    risk_engine: RiskRuleEngine
    masker: PiiMasker
    demo_cache: DemoCache
    redis: Any | None = None
    tag_vectors: dict[str, list[float]] = field(default_factory=dict)

    async def close(self) -> None:
        close_llm = getattr(self.llm, "aclose", None)
        if close_llm is not None:
            await close_llm()
        if self.redis is not None:
            await self.redis.aclose()


def build_services(
    settings: Settings,
    *,
    llm: LLMClient | None = None,
    budget: TokenBudget | None = None,
    demo_cache: DemoCache | None = None,
) -> Services:
    dictionary = load_dictionary()
    client = llm or get_llm(settings)
    redis_client = None
    if budget is None:
        if settings.redis_url:
            redis_client = redis_async.from_url(settings.redis_url, decode_responses=True)
            budget = RedisTokenBudget(redis_client, settings.llm_daily_token_cap)
        else:
            budget = MemoryTokenBudget(settings.llm_daily_token_cap)
    return Services(
        settings=settings,
        llm=client,
        gateway=LLMGateway(client, budget, CircuitBreaker(), ConcurrencyLimits()),
        budget=budget,
        dictionary=dictionary,
        prompts=load_prompts(),
        risk_engine=RiskRuleEngine(load_risk_lexicon()),
        masker=PiiMasker(load_pii_patterns()),
        demo_cache=demo_cache or DemoCache.load(enabled=settings.demo_mode),
        redis=redis_client,
    )
