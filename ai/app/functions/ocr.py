"""AI-07 학회 서류 OCR (P1). 비전 LLM으로 읽고 필드 형식을 코드로 검사한다."""

import re
import time
from datetime import date
from typing import TYPE_CHECKING

from app import errors
from app.functions.drafts import OcrDraft
from app.llm.client import sniff_media_type
from app.meta import build_meta
from app.schemas import OcrResponse

if TYPE_CHECKING:
    from app.services import Services

OCR_TIMEOUT_SECONDS = 10.0
OCR_MAX_BYTES = 10 * 1024 * 1024
MAX_FIELD_CHARS = 100
_CERT_NUMBER = re.compile(r"^[A-Za-z0-9가-힣][A-Za-z0-9가-힣\-_. ]{1,38}[A-Za-z0-9가-힣]$")
_DATE = re.compile(r"^(\d{4})[-./년]\s*(\d{1,2})[-./월]\s*(\d{1,2})일?$")


def parse_date(value: str | None) -> date | None:
    match = _DATE.match(value.strip()) if value else None
    if match is None:
        return None
    try:
        parsed = date(int(match[1]), int(match[2]), int(match[3]))
    except ValueError:
        return None
    return parsed if parsed <= date.today() else None


def _clean(value: str | None) -> str | None:
    cleaned = value.strip() if value else ""
    return cleaned[:MAX_FIELD_CHARS] or None


def _cert_number(value: str | None) -> str | None:
    cleaned = _clean(value)
    return cleaned if cleaned and _CERT_NUMBER.match(cleaned) else None


async def read_certificate(data: bytes, services: "Services") -> OcrResponse:
    started = time.perf_counter()
    if not data:
        raise errors.bad_request("empty file")
    if len(data) > OCR_MAX_BYTES:
        raise errors.payload_too_large()
    # 업로드가 알려 준 종류를 믿지 않고 파일 서명으로 확인한다 (이미지·PDF만).
    if sniff_media_type(data) is None:
        raise errors.bad_request("unsupported file type")
    prompt = services.prompts["read_certificate"]
    result = await services.gateway.vision(
        system=prompt.text, image=data, schema=OcrDraft, timeout=OCR_TIMEOUT_SECONDS
    )
    draft = result.parsed
    fields = {
        "name": _clean(draft.name),
        "cert_name": _clean(draft.cert_name),
        "cert_number": _cert_number(draft.cert_number),
        "issued_on": parse_date(draft.issued_on),
        "issuer": _clean(draft.issuer),
    }
    # 추출 API가 신뢰도를 주지 않으면, 형식 검사를 통과한 필드의 비율로 대신한다.
    filled = sum(value is not None for value in fields.values()) / len(fields)
    confidence = draft.confidence if draft.confidence is not None else filled
    return OcrResponse(
        **fields,
        confidence=round(min(max(confidence, 0.0), 1.0), 3),
        meta=build_meta(
            services,
            prompt.version,
            started,
            model=result.model,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
        ),
    )
