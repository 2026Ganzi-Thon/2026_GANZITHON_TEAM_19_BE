"""FastAPI 앱 (TRD 16-11).

uvicorn app.main:app --host 0.0.0.0 --port 8090 --workers 1 --no-access-log
스택 내부 네트워크에만 붙고 호스트 포트로는 열지 않는다.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.api import router
from app.config import get_settings
from app.errors import register_error_handlers
from app.logging import configure_logging, get_logger
from app.middleware import BodySizeLimitMiddleware, request_context
from app.services import Services, build_services

log = get_logger()


def create_app(services: Services | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        configure_logging()
        # 설정·프롬프트·사전이 하나라도 잘못되면 여기서 기동에 실패한다.
        app.state.services = services or build_services(get_settings())
        current: Services = app.state.services
        log.info(
            "ai_startup",
            provider=current.llm.provider,
            model=current.llm.model,
            demo_mode=current.settings.demo_mode,
            demo_cache_entries=len(current.demo_cache),
        )
        yield
        await current.close()

    app = FastAPI(
        title="MindCare AI Server",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,  # 계약은 ai/openapi.json 파일로만 공유한다
    )
    # 나중에 더한 미들웨어가 바깥에서 돈다. request_context가 가장 바깥이라
    # 413을 포함한 모든 응답에 요청 ID와 X-AI-Budget-Used가 붙는다 (TRD 16-11).
    app.add_middleware(BodySizeLimitMiddleware)
    app.middleware("http")(request_context)
    register_error_handlers(app)
    app.include_router(router)

    @app.get("/health", include_in_schema=False)
    async def health() -> dict[str, str]:
        """프로세스가 살아 있는지만 본다. 인증 없음, 내부 네트워크 전용."""
        return {"status": "ok"}

    @app.get("/ready", include_in_schema=False)
    async def ready() -> JSONResponse:
        """설정·프롬프트·사전 로드가 끝났고 Redis에 연결되는지 본다."""
        current: Services = app.state.services
        redis_ok = await current.budget.ping()
        status = "ok" if redis_ok else "unavailable"
        return JSONResponse(
            {"status": status, "redis": "ok" if redis_ok else "down"},
            status_code=200 if redis_ok else 503,
        )

    return app


app = create_app()
