"""X-Internal-Token 검증 (TRD 16-11). 토큰 목록 중 하나와 상수 시간 비교한다."""

import hmac

from fastapi import Header, Request

from app import errors

TOKEN_HEADER = "X-Internal-Token"  # noqa: S105 - 헤더 이름이지 비밀값이 아니다


def token_matches(candidate: str, allowed: tuple[str, ...]) -> bool:
    encoded = candidate.encode()
    matched = False
    for token in allowed:  # 일찍 끝내지 않고 전부 비교한다
        matched = hmac.compare_digest(encoded, token.encode()) or matched
    return matched


async def require_internal_token(
    request: Request,
    x_internal_token: str | None = Header(default=None, alias=TOKEN_HEADER),
) -> None:
    allowed: tuple[str, ...] = request.app.state.services.settings.internal_tokens
    if x_internal_token is None or not token_matches(x_internal_token, allowed):
        raise errors.unauthorized()
