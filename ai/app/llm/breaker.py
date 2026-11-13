"""AI 서버 → LLM 공급사 서킷 브레이커 (TRD 17-4).

1분 동안 10건 이상 중 50%가 실패하면 30초 차단하고, 그 뒤 1건만 시험한다.

호출마다 허가증(Permit)을 받고 결과를 그 허가증으로 돌려준다. 상태가 바뀌기 전에
시작한 호출의 결과가 뒤늦게 도착해도 지금 상태를 바꾸지 못한다.
"""

import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal

WINDOW_SECONDS = 60.0
MIN_CALLS = 10
FAILURE_RATE = 0.5
OPEN_SECONDS = 30.0

State = Literal["closed", "open", "half_open"]


@dataclass(frozen=True)
class Permit:
    generation: int  # 발급 당시의 상태 세대. 열리거나 닫힐 때마다 올라간다
    probe: bool  # half-open 시험 호출인지


class CircuitBreaker:
    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._calls: deque[tuple[float, bool]] = deque()
        self._opened_at: float | None = None
        self._probing = False
        self._generation = 0

    @property
    def state(self) -> State:
        if self._opened_at is None:
            return "closed"
        if self._clock() - self._opened_at >= OPEN_SECONDS:
            return "half_open"
        return "open"

    def allow(self) -> Permit | None:
        """호출해도 되면 허가증을, 차단 중이면 None을 돌려준다."""
        state = self.state
        if state == "closed":
            return Permit(self._generation, probe=False)
        if state == "half_open" and not self._probing:
            self._probing = True
            return Permit(self._generation, probe=True)
        return None

    def record(self, permit: Permit, ok: bool | None) -> None:
        """결과를 기록한다. ok=None이면 세지 않는다 (대기열 시간 초과, 4xx, 취소).

        허가증을 받은 호출은 어떤 경우에도 한 번은 이 함수를 불러야 한다.
        그래야 시험 호출 자리가 풀린다.
        """
        if permit.generation != self._generation:
            return  # 상태가 바뀌기 전에 시작한 호출
        now = self._clock()
        if permit.probe:
            self._probing = False
            if ok is None:
                return  # 시험이 판정 없이 끝남. 다음 호출이 다시 시험한다
            if ok:
                self._opened_at = None
                self._calls.clear()
            else:
                self._opened_at = now
            self._generation += 1
            return
        if ok is None:
            return
        self._calls.append((now, ok))
        while self._calls and now - self._calls[0][0] > WINDOW_SECONDS:
            self._calls.popleft()
        failures = sum(1 for _, call_ok in self._calls if not call_ok)
        if len(self._calls) >= MIN_CALLS and failures / len(self._calls) >= FAILURE_RATE:
            self._opened_at = now
            self._generation += 1
