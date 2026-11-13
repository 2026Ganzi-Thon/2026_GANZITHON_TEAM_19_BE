"""본문 크기 제한, 내부 토큰 확인, 요청 ID·예산 헤더, 예상 밖 오류 처리 (TRD 16-11)."""

import re
import uuid
from collections.abc import Awaitable, Callable

import structlog
from fastapi import Request, Response
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app import errors
from app.logging import get_logger
from app.security import TOKEN_HEADER, token_matches

JSON_BODY_LIMIT = 1 * 1024 * 1024
OCR_BODY_LIMIT = 10 * 1024 * 1024 + 64 * 1024  # 파일 10MB + multipart 경계 여유
OCR_PATH = "/v1/ocr/certificate"
V1_PREFIX = "/v1/"
REQUEST_ID_HEADER = "X-Request-ID"
BUDGET_HEADER = "X-AI-Budget-Used"
_REQUEST_ID = re.compile(r"[A-Za-z0-9_.:\-]{1,128}")
_TOO_LARGE_BODY = (
    b'{"error":{"code":"AI_PAYLOAD_TOO_LARGE","message":"payload too large","retryable":false}}'
)

log = get_logger()


class BodySizeLimitMiddleware:
    """JSON 본문 1MB, OCR 파일 10MB를 넘으면 413. Content-Length가 없어도 읽으면서 센다."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        limit = OCR_BODY_LIMIT if scope["path"] == OCR_PATH else JSON_BODY_LIMIT
        declared = dict(scope["headers"]).get(b"content-length")
        if declared is not None and declared.isdigit() and int(declared) > limit:
            await self._reject(send)
            return

        received = 0
        overflow = False
        started = False
        rejected = False

        async def counting_receive() -> Message:
            nonlocal received, overflow
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    overflow = True
                    raise errors.payload_too_large()  # 더 읽지 않게 본문 읽기를 끊는다
            return message

        async def guarded_send(message: Message) -> None:
            # 본문 읽기 오류는 프레임워크가 400으로 바꿔 보내므로, 여기서 413으로 바꾼다.
            nonlocal started, rejected
            if rejected:
                return  # 413을 이미 보냈다. 앱이 보내려던 응답은 버린다
            if overflow and not started:
                rejected = True
                await self._reject(send)
                return
            started = started or message["type"] == "http.response.start"
            await send(message)

        await self.app(scope, counting_receive, guarded_send)

    async def _reject(self, send: Send) -> None:
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [(b"content-type", b"application/json")],
            }
        )
        await send({"type": "http.response.body", "body": _TOO_LARGE_BODY})


async def request_context(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    """요청마다 한 번 도는 바깥 껍질.

    - 백엔드가 넘긴 X-Request-ID를 로그에 그대로 쓴다.
    - /v1/* 은 본문을 읽기 전에 내부 토큰부터 확인한다.
    - 예상 밖 오류는 종류만 로그에 남기고 500으로 답한다 (메시지·스택은 남기지 않는다).
    - 모든 응답에 일일 토큰 사용률을 붙인다.
    """
    incoming = request.headers.get(REQUEST_ID_HEADER, "")
    request_id = incoming if _REQUEST_ID.fullmatch(incoming) else uuid.uuid4().hex
    structlog.contextvars.clear_contextvars()
    structlog.contextvars.bind_contextvars(request_id=request_id)
    services = request.app.state.services

    response: Response
    token = request.headers.get(TOKEN_HEADER)
    allowed = services.settings.internal_tokens
    if request.url.path.startswith(V1_PREFIX) and not (token and token_matches(token, allowed)):
        log.warning("ai_call", fn="unknown", ok=False, code="AI_UNAUTHORIZED", status=401)
        response = errors.error_response(errors.unauthorized())
    else:
        try:
            response = await call_next(request)
        except Exception as exc:
            log.error(
                "ai_call",
                fn=errors.route_name(request),
                ok=False,
                code=errors.INTERNAL_CODE,
                status=500,
                error_type=type(exc).__name__,
            )
            response = errors.internal_error_response()

    response.headers[REQUEST_ID_HEADER] = request_id
    used = await services.budget.used_ratio()
    response.headers[BUDGET_HEADER] = f"{used:.3f}"
    return response
