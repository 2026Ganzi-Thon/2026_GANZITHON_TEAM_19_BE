"""LLM 동시 호출 상한 8 = Red Flag 전용 4 + 나머지 4 (TRD 16-11).

리포트가 몰려도 위험 판정이 밀리지 않게 차선을 나눈다.
"""

import asyncio
from typing import Literal

Lane = Literal["risk", "general"]

RISK_CONCURRENCY = 4
GENERAL_CONCURRENCY = 4


class ConcurrencyLimits:
    def __init__(self, risk: int = RISK_CONCURRENCY, general: int = GENERAL_CONCURRENCY) -> None:
        self._lanes: dict[Lane, asyncio.Semaphore] = {
            "risk": asyncio.Semaphore(risk),
            "general": asyncio.Semaphore(general),
        }

    def lane(self, name: Lane) -> asyncio.Semaphore:
        return self._lanes[name]
