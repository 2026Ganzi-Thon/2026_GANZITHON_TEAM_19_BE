"""LLM 계층: 서킷 브레이커, 일일 토큰 상한, 게이트웨이 재시도·제한 시간."""

import asyncio
from datetime import datetime
from typing import Any

import pytest
from pydantic import BaseModel

from app import errors
from app.config import Settings
from app.llm.breaker import MIN_CALLS, OPEN_SECONDS, CircuitBreaker
from app.llm.budget import KST, MemoryTokenBudget, today_key
from app.llm.client import LLMOutputInvalid, LLMProviderError, LLMRateLimited, LLMResult
from app.llm.fake import hash_embed
from app.llm.gateway import LLMGateway
from app.llm.limits import ConcurrencyLimits
from app.security import token_matches
from tests.conftest import TEST_TOKEN, ScriptedLLM, make_settings


class Answer(BaseModel):
    value: int


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def make_gateway(llm: Any, cap: int = 1_000_000) -> tuple[LLMGateway, list[float]]:
    sleeps: list[float] = []

    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    gateway = LLMGateway(
        llm, MemoryTokenBudget(cap), CircuitBreaker(), ConcurrencyLimits(), sleep=sleep
    )
    return gateway, sleeps


async def call(gateway: LLMGateway, timeout: float = 30.0) -> LLMResult[Answer]:
    return await gateway.complete(
        system="s", user="u", schema=Answer, timeout=timeout, max_tokens=10
    )


async def test_gateway_retries_once_with_error_note_on_invalid_schema() -> None:
    llm = ScriptedLLM(LLMOutputInvalid("value: int_parsing"), {"value": 7})
    gateway, _ = make_gateway(llm)

    result = await call(gateway)

    assert result.parsed.value == 7
    assert "value: int_parsing" in llm.prompts[1]


async def test_gateway_gives_up_after_second_invalid_output() -> None:
    gateway, _ = make_gateway(ScriptedLLM(LLMOutputInvalid("bad")))

    with pytest.raises(errors.AIError) as caught:
        await call(gateway)

    assert (caught.value.status, caught.value.code) == (502, "AI_OUTPUT_INVALID")


async def test_gateway_backs_off_2s_then_4s_on_rate_limit() -> None:
    llm = ScriptedLLM(LLMRateLimited("429"), LLMProviderError("503"), {"value": 1})
    gateway, sleeps = make_gateway(llm)

    result = await call(gateway)

    assert result.parsed.value == 1
    assert sleeps == [2.0, 4.0]


async def test_gateway_skips_backoff_when_time_limit_is_too_short() -> None:
    gateway, sleeps = make_gateway(ScriptedLLM(LLMRateLimited("429")))

    with pytest.raises(errors.AIError) as caught:
        await call(gateway, timeout=2.5)

    assert (caught.value.status, caught.value.code) == (429, "AI_RATE_LIMITED")
    assert sleeps == []


async def test_gateway_does_not_retry_non_retryable_provider_error() -> None:
    gateway, sleeps = make_gateway(ScriptedLLM(LLMProviderError("400", retryable=False)))

    with pytest.raises(errors.AIError) as caught:
        await call(gateway)

    assert caught.value.code == "AI_PROVIDER_ERROR"
    assert sleeps == []


async def test_gateway_times_out_slow_call() -> None:
    class SlowLLM(ScriptedLLM):
        async def complete_json(self, *args: Any, **kwargs: Any) -> Any:
            await asyncio.sleep(1)

    gateway, _ = make_gateway(SlowLLM({"value": 1}))

    with pytest.raises(errors.AIError) as caught:
        await call(gateway, timeout=0.05)

    assert (caught.value.status, caught.value.code) == (504, "AI_TIMEOUT")


async def test_gateway_counts_tokens_and_blocks_when_cap_is_reached() -> None:
    gateway, _ = make_gateway(ScriptedLLM({"value": 1}), cap=120)

    await call(gateway)  # 100 + 20 토큰
    with pytest.raises(errors.AIError) as caught:
        await call(gateway)

    assert caught.value.code == "AI_BUDGET_EXCEEDED"
    assert await gateway.budget.used_ratio() == 1.0


def fail_times(breaker: CircuitBreaker, count: int) -> None:
    for _ in range(count):
        permit = breaker.allow()
        assert permit is not None
        breaker.record(permit, False)


def test_breaker_opens_at_half_failures_and_probes_after_30s() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(clock)
    for index in range(MIN_CALLS):
        permit = breaker.allow()
        assert permit is not None
        breaker.record(permit, index % 2 == 0)

    assert breaker.state == "open"
    assert breaker.allow() is None

    clock.now += OPEN_SECONDS
    probe = breaker.allow()  # 시험 호출 1건
    assert probe is not None and probe.probe is True
    assert breaker.allow() is None
    breaker.record(probe, True)
    assert breaker.state == "closed"


def test_breaker_stays_closed_below_minimum_calls() -> None:
    breaker = CircuitBreaker(FakeClock())
    fail_times(breaker, MIN_CALLS - 1)

    assert breaker.state == "closed"


def test_breaker_reopens_when_probe_fails() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(clock)
    fail_times(breaker, MIN_CALLS)
    clock.now += OPEN_SECONDS
    probe = breaker.allow()
    assert probe is not None

    breaker.record(probe, False)

    assert breaker.state == "open"


def test_breaker_ignores_results_of_calls_started_before_it_opened() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(clock)
    straggler = breaker.allow()
    assert straggler is not None
    fail_times(breaker, MIN_CALLS)

    breaker.record(straggler, True)  # 열리기 전에 시작한 호출이 뒤늦게 성공

    assert breaker.state == "open"


def test_breaker_frees_probe_slot_when_probe_ends_without_verdict() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(clock)
    fail_times(breaker, MIN_CALLS)
    clock.now += OPEN_SECONDS
    probe = breaker.allow()
    assert probe is not None

    breaker.record(probe, None)  # 취소·대기열 시간 초과

    assert breaker.allow() is not None


async def test_budget_resets_when_kst_date_changes() -> None:
    now = [datetime(2026, 11, 12, 23, 59, tzinfo=KST)]
    budget = MemoryTokenBudget(100, clock=lambda: now[0])
    await budget.add(100)
    assert await budget.exhausted() is True

    now[0] = datetime(2026, 11, 13, 0, 1, tzinfo=KST)

    assert await budget.exhausted() is False


def test_budget_key_uses_kst_date() -> None:
    assert today_key(datetime(2026, 11, 12, 16, 0, tzinfo=KST)) == "ai:tokens:2026-11-12"


def test_hash_embed_is_deterministic_and_normalized() -> None:
    first, second = hash_embed(["취업 스트레스", "취업 스트레스"])

    assert first == second
    assert sum(v * v for v in first) == pytest.approx(1.0)


def test_token_match_accepts_any_listed_token_only() -> None:
    allowed = (TEST_TOKEN, "second-token-0123456789abcdef0123456789")

    assert token_matches(allowed[1], allowed) is True
    assert token_matches("nope", allowed) is False


@pytest.mark.parametrize(
    "overrides",
    [
        {"ai_internal_tokens": ""},
        {"ai_internal_tokens": "short"},
        {"llm_provider": "upstage"},
        {"llm_provider": "upstage", "llm_api_key": "test-key"},
        {"llm_provider": "anthropic"},
        {"llm_provider": "openai"},
        {"llm_daily_token_cap": 0},
    ],
)
def test_settings_fail_fast_on_bad_config(overrides: dict[str, Any]) -> None:
    with pytest.raises(ValueError):
        make_settings(**overrides)


def test_settings_accept_demo_mode_on() -> None:
    settings: Settings = make_settings(demo_mode="on")

    assert settings.demo_mode is True
