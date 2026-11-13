"""내부 API 오류 규약 (TRD 16-11). 오류 메시지에는 입력 문자열을 넣지 않는다."""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.logging import get_logger

log = get_logger()

INTERNAL_CODE = "AI_INTERNAL"


class AIError(Exception):
    def __init__(
        self, status: int, code: str, message: str, *, retryable: bool, outage: bool = False
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.outage = outage  # 공급사 장애로 보고 서킷 브레이커 실패율에 넣을지


def bad_request(message: str = "invalid request", status: int = 400) -> AIError:
    return AIError(status, "AI_BAD_REQUEST", message, retryable=False)


def unauthorized() -> AIError:
    return AIError(401, "AI_UNAUTHORIZED", "invalid internal token", retryable=False)


def payload_too_large() -> AIError:
    return AIError(413, "AI_PAYLOAD_TOO_LARGE", "payload too large", retryable=False)


def budget_exceeded() -> AIError:
    return AIError(429, "AI_BUDGET_EXCEEDED", "daily token cap reached", retryable=False)


def rate_limited() -> AIError:
    return AIError(429, "AI_RATE_LIMITED", "LLM provider rate limit", retryable=True, outage=True)


def provider_error(*, outage: bool = True) -> AIError:
    """응답의 retryable은 TRD 16-11 표대로 항상 true다.

    outage=False는 공급사가 요청 자체를 거절한 경우다 (4xx, 정책 거절).
    이때는 서킷 브레이커 실패율에 넣지 않는다.
    """
    return AIError(502, "AI_PROVIDER_ERROR", "LLM provider error", retryable=True, outage=outage)


def output_invalid() -> AIError:
    return AIError(502, "AI_OUTPUT_INVALID", "LLM output failed validation", retryable=True)


def unavailable() -> AIError:
    return AIError(503, "AI_UNAVAILABLE", "LLM circuit open", retryable=True)


def timeout() -> AIError:
    return AIError(504, "AI_TIMEOUT", "function time limit exceeded", retryable=True, outage=True)


def error_body(code: str, message: str, retryable: bool) -> dict[str, dict[str, object]]:
    return {"error": {"code": code, "message": message, "retryable": retryable}}


def error_response(exc: AIError) -> JSONResponse:
    return JSONResponse(error_body(exc.code, exc.message, exc.retryable), status_code=exc.status)


def internal_error_response() -> JSONResponse:
    return JSONResponse(error_body(INTERNAL_CODE, "internal error", False), status_code=500)


def route_name(request: Request) -> str:
    route = request.scope.get("route")
    return str(getattr(route, "name", "unknown"))


async def _handle_ai_error(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, AIError)  # noqa: S101 - 핸들러 등록으로 보장
    log.warning("ai_call", fn=route_name(request), ok=False, code=exc.code, status=exc.status)
    return error_response(exc)


async def _handle_validation_error(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)  # noqa: S101
    # 입력 값은 빼고 위치와 오류 종류만 알려 준다.
    fields = sorted({".".join(str(p) for p in e["loc"]) + ":" + e["type"] for e in exc.errors()})
    message = "invalid fields: " + ", ".join(fields[:10])
    log.warning("ai_call", fn=route_name(request), ok=False, code="AI_BAD_REQUEST", status=422)
    return JSONResponse(error_body("AI_BAD_REQUEST", message, False), status_code=422)


def register_error_handlers(app: FastAPI) -> None:
    """예상 밖 오류(500)는 여기 등록하지 않고 app.middleware.request_context가 잡는다.

    Starlette는 Exception 핸들러를 부른 뒤 예외를 다시 던져서, uvicorn이 예외 메시지와
    스택을 stderr에 찍는다. 예외 메시지에는 입력 값이 섞일 수 있다.
    """
    app.add_exception_handler(AIError, _handle_ai_error)
    app.add_exception_handler(RequestValidationError, _handle_validation_error)
