"""일일 토큰 상한 (TRD 16-9). Redis DB 1의 ai:tokens:{YYYY-MM-DD} (KST 날짜) 카운터."""

from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any, Protocol

from app.logging import get_logger

KST = timezone(timedelta(hours=9))
KEY_TTL_SECONDS = 48 * 3600

log = get_logger()


def today_key(now: datetime | None = None) -> str:
    current = now or datetime.now(KST)
    return f"ai:tokens:{current.astimezone(KST):%Y-%m-%d}"


class TokenBudget(Protocol):
    async def add(self, tokens: int) -> None: ...
    async def used_ratio(self) -> float: ...
    async def exhausted(self) -> bool: ...
    async def ping(self) -> bool: ...


class MemoryTokenBudget:
    """Redis가 없는 로컬·테스트용, 그리고 Redis 장애 때의 임시 카운터."""

    def __init__(self, cap: int, clock: Callable[[], datetime] | None = None) -> None:
        self._cap = cap
        self._clock = clock or (lambda: datetime.now(KST))
        self._key = today_key(self._clock())
        self._used = 0

    def _roll(self) -> None:
        key = today_key(self._clock())
        if key != self._key:
            self._key, self._used = key, 0

    async def add(self, tokens: int) -> None:
        self._roll()
        self._used += max(tokens, 0)

    async def used_ratio(self) -> float:
        self._roll()
        return min(self._used / self._cap, 1.0)

    async def exhausted(self) -> bool:
        return await self.used_ratio() >= 1.0

    async def ping(self) -> bool:
        return True


class RedisTokenBudget:
    def __init__(self, redis: Any, cap: int) -> None:
        self._redis = redis
        self._cap = cap
        # Redis가 잠깐 끊겨도 세던 값을 잃지 않게 한다. 장애 중 상한은 이 값으로 판단한다.
        self._shadow = MemoryTokenBudget(cap)

    async def add(self, tokens: int) -> None:
        await self._shadow.add(tokens)
        try:
            key = today_key()
            await self._redis.incrby(key, max(tokens, 0))
            await self._redis.expire(key, KEY_TTL_SECONDS)
        except Exception as exc:
            log.warning("budget_redis_error", op="add", error_type=type(exc).__name__)

    async def used_ratio(self) -> float:
        try:
            raw = await self._redis.get(today_key())
        except Exception as exc:
            log.warning("budget_redis_error", op="get", error_type=type(exc).__name__)
            return await self._shadow.used_ratio()
        return min(int(raw or 0) / self._cap, 1.0)

    async def exhausted(self) -> bool:
        return await self.used_ratio() >= 1.0

    async def ping(self) -> bool:
        try:
            return bool(await self._redis.ping())
        except Exception:
            return False
