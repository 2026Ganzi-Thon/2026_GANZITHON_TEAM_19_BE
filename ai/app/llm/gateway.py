"""LLM 호출 공통 경로: 일일 상한 → 서킷 → 동시 호출 상한 → 제한 시간 → 재시도 → 토큰 기록.

채팅(JSON 출력), 서류 추출, 임베딩이 모두 이 경로를 지난다.
"""

import asyncio
import time
from collections.abc import Awaitable, Callable

from pydantic import BaseModel

from app import errors
from app.llm.breaker import CircuitBreaker
from app.llm.budget import TokenBudget
from app.llm.client import (
    LLMClient,
    LLMError,
    LLMOutputInvalid,
    LLMProviderError,
    LLMRateLimited,
    LLMResult,
    LLMTimeout,
)
from app.llm.limits import ConcurrencyLimits, Lane
from app.logging import get_logger

# 429·5xx는 2초·4초 백오프로 2회까지 다시 부른다 (TRD 17-4). 남은 시간이 모자라면 포기한다.
BACKOFF_SECONDS = (2.0, 4.0)
MIN_ATTEMPT_SECONDS = 1.0
SCHEMA_RETRY_NOTE = (
    "\n\n이전 응답이 출력 스키마 검증에 실패했습니다. 오류: {detail}\n"
    "스키마에 정확히 맞는 JSON으로 다시 답하세요."
)

log = get_logger()


class _Spend:
    """한 호출에서 쓴 토큰. 실패한 시도의 토큰도 일일 상한에 넣기 위해 모은다."""

    def __init__(self) -> None:
        self.tokens = 0


def to_ai_error(exc: LLMError) -> errors.AIError:
    """공급자 오류를 내부 API 오류(TRD 16-11 표)로 바꾼다."""
    if isinstance(exc, LLMTimeout):
        return errors.timeout()
    if isinstance(exc, LLMRateLimited):
        return errors.rate_limited()
    if isinstance(exc, LLMOutputInvalid):
        return errors.output_invalid()
    if isinstance(exc, LLMProviderError) and exc.retryable:
        return errors.provider_error()
    # 공급사가 요청 자체를 거절함 (4xx, 정책 거절). 서킷 실패율에는 넣지 않는다.
    return errors.provider_error(outage=False)


class LLMGateway:
    def __init__(
        self,
        llm: LLMClient,
        budget: TokenBudget,
        breaker: CircuitBreaker,
        limits: ConcurrencyLimits,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.llm = llm
        self.budget = budget
        self._breaker = breaker
        self._limits = limits
        self._clock = clock
        self._sleep = sleep

    async def complete[T: BaseModel](
        self,
        *,
        system: str,
        user: str,
        schema: type[T],
        timeout: float,
        max_tokens: int,
        lane: Lane = "general",
    ) -> LLMResult[T]:
        async def attempt(note: str, remaining: float) -> LLMResult[T]:
            return await self.llm.complete_json(system, user + note, schema, remaining, max_tokens)

        return await self._run(attempt, timeout, lane)

    async def vision[T: BaseModel](
        self,
        *,
        system: str,
        image: bytes,
        schema: type[T],
        timeout: float,
    ) -> LLMResult[T]:
        async def attempt(note: str, remaining: float) -> LLMResult[T]:
            return await self.llm.vision_json(system + note, image, schema, remaining)

        return await self._run(attempt, timeout, "general")

    async def embed(self, texts: list[str], *, timeout: float) -> list[list[float]]:
        """임베딩. 실패하면 오류를 돌려주고, 폴백은 백엔드가 한다 (TRD 16-1 AI-02)."""

        async def call(_deadline: float, spend: _Spend) -> list[list[float]]:
            try:
                vectors = await self.llm.embed(texts)
            except LLMError as exc:
                raise to_ai_error(exc) from None
            if len(vectors) != len(texts):
                raise errors.output_invalid()
            # embed()는 벡터만 돌려주므로 토큰 수를 글자 수로 어림해 일일 상한에 넣는다.
            spend.tokens += sum(len(text) for text in texts)
            return vectors

        # 차선은 LLM 호출 몫이다 (TRD 16-11). 임베딩까지 넣으면 60~90초짜리 리포트가
        # 차선을 채운 동안 3초짜리 매칭이 전부 시간 초과로 끝난다.
        return await self._guarded(call, timeout, None)

    async def _run[T: BaseModel](
        self,
        attempt: Callable[[str, float], Awaitable[LLMResult[T]]],
        timeout: float,
        lane: Lane,
    ) -> LLMResult[T]:
        async def call(deadline: float, spend: _Spend) -> LLMResult[T]:
            result = await self._attempts(attempt, deadline, spend)
            spend.tokens += result.tokens_in + result.tokens_out
            return result

        return await self._guarded(call, timeout, lane)

    async def _guarded[R](
        self,
        call: Callable[[float, _Spend], Awaitable[R]],
        timeout: float,
        lane: Lane | None,
    ) -> R:
        """일일 상한·서킷·차선·제한 시간을 건 채로 call을 한 번 실행한다.

        lane이 None이면 차선을 쓰지 않는다.
        """
        if await self.budget.exhausted():
            raise errors.budget_exceeded()
        permit = self._breaker.allow()
        if permit is None:
            raise errors.unavailable()

        deadline = self._clock() + timeout
        semaphore = self._limits.lane(lane) if lane is not None else None
        outcome: bool | None = None  # None이면 서킷 통계에 넣지 않는다
        acquired = False
        spend = _Spend()
        try:
            if semaphore is not None:
                try:
                    async with asyncio.timeout(timeout):
                        await semaphore.acquire()
                        acquired = True
                except TimeoutError:
                    # 우리 쪽 대기열에서 보낸 시간이다. 공급사 장애로 세지 않는다.
                    raise errors.timeout() from None
                if deadline - self._clock() < min(MIN_ATTEMPT_SECONDS, timeout / 2):
                    # 차선을 기다리느라 시간을 거의 다 썼다. 지금 불러 봐야 시간 초과가
                    # 되고 그것이 공급사 장애로 세어지므로, 부르지 않고 끝낸다.
                    raise errors.timeout()
            try:
                async with asyncio.timeout(max(deadline - self._clock(), 0.0)):
                    result = await call(deadline, spend)
            except TimeoutError:
                outcome = False
                raise errors.timeout() from None
            except errors.AIError as exc:
                outcome = False if exc.outage else None
                raise
            except Exception as exc:
                # 클라이언트가 변환하지 못한 오류. 메시지에는 입력이 섞일 수 있어 종류만 남긴다.
                outcome = False
                log.error("llm_unexpected_error", error_type=type(exc).__name__)
                raise errors.provider_error() from None
            outcome = True
            return result
        finally:
            # 취소를 포함해 어떤 경로로 끝나도 자리와 허가증을 돌려준다.
            if acquired and semaphore is not None:
                semaphore.release()
            self._breaker.record(permit, outcome)
            if spend.tokens:
                await self.budget.add(spend.tokens)

    async def _attempts[T: BaseModel](
        self,
        attempt: Callable[[str, float], Awaitable[LLMResult[T]]],
        deadline: float,
        spend: _Spend,
    ) -> LLMResult[T]:
        note = ""
        schema_retried = False
        backoffs = list(BACKOFF_SECONDS)
        while True:
            remaining = deadline - self._clock()
            if remaining <= 0:
                raise errors.timeout()
            try:
                return await attempt(note, remaining)
            except LLMOutputInvalid as exc:
                spend.tokens += exc.tokens
                if schema_retried:
                    raise to_ai_error(exc) from None
                schema_retried = True
                note = SCHEMA_RETRY_NOTE.format(detail=exc.detail)
            except LLMError as exc:
                failure = to_ai_error(exc)
                wait = backoffs.pop(0) if backoffs else None
                left = deadline - self._clock()
                # 다시 불러 볼 만한 것은 429와 5xx뿐이다 (서킷에 넣는 오류와 같다).
                retryable = failure.outage and not isinstance(exc, LLMTimeout)
                if not retryable or wait is None or left < wait + MIN_ATTEMPT_SECONDS:
                    raise failure from None
                await self._sleep(wait)
