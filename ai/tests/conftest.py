"""공통 픽스처. 실 LLM·Redis 없이 fake LLM과 메모리 카운터로 돈다."""

from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app.config import Settings
from app.llm.budget import MemoryTokenBudget
from app.llm.client import LLMClient, LLMResult
from app.main import create_app
from app.services import Services, build_services

TEST_TOKEN = "test-internal-token-0123456789abcdef"
HEADERS = {"X-Internal-Token": TEST_TOKEN}
# 파일 서명(매직 넘버)만 맞춘 가짜 서류
PNG_BYTES = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + b"fake image"
PDF_BYTES = b"%PDF-1.7 fake document"


def make_settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {"ai_internal_tokens": TEST_TOKEN, "llm_provider": "fake"}
    return Settings(**{**values, **overrides})


class ScriptedLLM:
    """정해 둔 응답을 차례로 돌려주는 테스트용 LLM. 예외를 넣으면 그대로 던진다."""

    provider = "scripted"
    model = "scripted-1"

    def __init__(self, *outcomes: Any) -> None:
        self._outcomes = list(outcomes)
        self.prompts: list[str] = []

    async def complete_json[T: BaseModel](
        self, system: str, user: str, schema: type[T], timeout: float, max_tokens: int = 1500
    ) -> LLMResult[T]:
        self.prompts.append(user)
        outcome = self._outcomes.pop(0) if len(self._outcomes) > 1 else self._outcomes[0]
        if isinstance(outcome, Exception):
            raise outcome
        return LLMResult(
            parsed=schema.model_validate(outcome), tokens_in=100, tokens_out=20, model=self.model
        )

    async def vision_json[T: BaseModel](
        self, system: str, image: bytes, schema: type[T], timeout: float
    ) -> LLMResult[T]:
        return await self.complete_json(system, "", schema, timeout)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise NotImplementedError


def make_services(llm: LLMClient | None = None, **settings: Any) -> Services:
    config = make_settings(**settings)
    return build_services(config, llm=llm, budget=MemoryTokenBudget(config.llm_daily_token_cap))


@pytest.fixture
def services() -> Services:
    return make_services()


@pytest.fixture
def client(services: Services) -> Iterator[TestClient]:
    with TestClient(create_app(services)) as test_client:
        yield test_client
