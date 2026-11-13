"""JSON 한 줄 로그. 요청·응답 본문과 입력 문자열은 기록하지 않는다 (TRD 16-10, 16-11)."""

import logging
import sys
from collections.abc import MutableMapping
from typing import Any

import structlog

# 본문이 들어갈 수 있는 필드 이름은 로그에서 지운다.
BLOCKED_FIELDS = frozenset(
    {
        "text",
        "transcript",
        "body",
        "content",
        "prompt",
        "system",
        "user",
        "evidence",
        "input",
        "output",
        "context",
        "digest",
        "message",
        "exception",
        "exc_info",
    }
)


def _drop_body_fields(
    _logger: Any, _method: str, event: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    for key in BLOCKED_FIELDS & event.keys():
        del event[key]
    return event


def configure_logging() -> None:
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            _drop_body_fields,
            structlog.processors.JSONRenderer(ensure_ascii=False),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=False,
    )


def get_logger() -> Any:
    return structlog.get_logger("ai")
