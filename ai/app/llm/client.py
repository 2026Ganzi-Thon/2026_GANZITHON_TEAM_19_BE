"""LLM 클라이언트 인터페이스와 공급자 선택 (TRD 16-7)."""

from dataclasses import dataclass
from typing import Protocol

from pydantic import BaseModel, ValidationError

from app.config import Settings, get_settings
from app.resources import load_dictionary

PDF_MEDIA_TYPE = "application/pdf"


def sniff_media_type(data: bytes) -> str | None:
    """파일 앞부분의 서명으로 종류를 알아낸다. 서류 이미지(PNG·JPEG)와 PDF만 받는다."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"%PDF-"):
        return PDF_MEDIA_TYPE
    return None


def validation_summary(exc: ValidationError) -> str:
    """스키마 검증 오류를 입력 값 없이 위치와 종류만으로 요약한다."""
    return "; ".join(
        ".".join(str(p) for p in e["loc"]) + ": " + e["type"] for e in exc.errors()[:10]
    )


@dataclass(frozen=True)
class LLMResult[T: BaseModel]:
    """파싱된 모델과 토큰 수."""

    parsed: T
    tokens_in: int
    tokens_out: int
    model: str


class LLMError(Exception):
    """공급자 호출 실패의 공통 부모."""


class LLMRateLimited(LLMError):
    pass


class LLMProviderError(LLMError):
    def __init__(self, reason: str, *, retryable: bool = True) -> None:
        super().__init__(reason)
        self.retryable = retryable


class LLMTimeout(LLMError):
    pass


class LLMOutputInvalid(LLMError):
    """스키마 검증 실패. detail에는 입력 값을 넣지 않는다.

    tokens는 이 시도가 쓴 토큰 수다 (알 수 있을 때). 실패해도 일일 상한에 넣는다.
    """

    def __init__(self, detail: str, tokens: int = 0) -> None:
        super().__init__(detail)
        self.detail = detail
        self.tokens = tokens


class LLMClient(Protocol):
    provider: str
    model: str

    async def complete_json[T: BaseModel](
        self, system: str, user: str, schema: type[T], timeout: float, max_tokens: int = 1500
    ) -> LLMResult[T]: ...

    async def embed(self, texts: list[str]) -> list[list[float]]: ...

    async def vision_json[T: BaseModel](
        self, system: str, image: bytes, schema: type[T], timeout: float
    ) -> LLMResult[T]: ...


def get_llm(settings: Settings | None = None) -> LLMClient:
    """settings.llm_provider: upstage | fake (부록 H-03에서 Upstage로 결정)"""
    current = settings or get_settings()
    # 공급자 모듈이 이 파일의 타입을 쓰므로 여기서 늦게 불러온다.
    if current.llm_provider == "upstage":
        from app.llm.upstage_client import UpstageLLMClient

        if current.llm_api_key is None or not current.llm_model:
            raise ValueError("LLM_API_KEY and LLM_MODEL are required")  # config 검증에서 걸러진다
        return UpstageLLMClient(
            api_key=current.llm_api_key.get_secret_value(), model=current.llm_model
        )
    from app.llm.fake import FakeLLMClient

    return FakeLLMClient(load_dictionary(), latency_ms=current.fake_llm_latency_ms)
