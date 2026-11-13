"""라우터 공통 의존성: 서비스 주입, 시연 캐시 조회, 호출 로그."""

from collections.abc import Awaitable, Callable
from typing import Annotated, Protocol

from fastapi import Depends, Request
from pydantic import BaseModel

from app.demo_cache.store import input_hash
from app.logging import get_logger
from app.schemas import Meta
from app.services import Services

log = get_logger()


def get_services(request: Request) -> Services:
    services: Services = request.app.state.services
    return services


ServicesDep = Annotated[Services, Depends(get_services)]


class HasMeta(Protocol):
    meta: Meta


def log_call(fn: str, meta: Meta) -> None:
    """한 줄 로그. 본문은 남기지 않고 메타데이터만 남긴다."""
    log.info("ai_call", fn=fn, ok=True, **meta.model_dump())


async def cached_or_compute[T: BaseModel](
    services: Services,
    fn: str,
    key_source: BaseModel | bytes,
    model: type[T],
    compute: Callable[[], Awaitable[T]],
) -> T:
    """DEMO_MODE에서 입력 해시가 정확히 같으면 검수된 캐시를, 아니면 실제 계산을 쓴다."""
    cached = services.demo_cache.get(fn, input_hash(key_source))
    if cached is None:
        return await compute()
    response = model.model_validate(cached)
    meta: Meta = response.meta  # type: ignore[attr-defined]
    return response.model_copy(update={"meta": meta.model_copy(update={"cache_hit": True})})
