"""실 공급자 경로: Upstage 클라이언트의 요청 형태·오류 변환과 Redis 토큰 카운터.

네트워크 없이 SDK 클라이언트와 Redis를 대역으로 바꿔 검사한다.
요청 형태의 기준은 Upstage 공식 문서(console.upstage.ai/docs)다.
"""

import base64
import json
from types import SimpleNamespace
from typing import Any

import httpx2
import openai
import pytest
from pydantic import BaseModel

from app.functions import drafts
from app.llm.budget import RedisTokenBudget, today_key
from app.llm.client import LLMOutputInvalid, LLMProviderError, LLMRateLimited, LLMTimeout
from app.llm.upstage_client import (
    BASE_URL,
    EMBED_BATCH_SIZE,
    EMBEDDING_MODEL,
    EXTRACTION_BASE_URL,
    EXTRACTION_MODEL,
    TEMPERATURE,
    UpstageLLMClient,
    strict_schema,
)
from tests.conftest import PDF_BYTES, PNG_BYTES

MAX_SCHEMA_DEPTH = 10
UNSUPPORTED_KEYWORDS = {"anyOf", "allOf", "oneOf", "not", "title", "default", "patternProperties"}
DRAFT_MODELS = [
    drafts.TagDraft,
    drafts.RiskDraft,
    drafts.SessionDraft,
    drafts.ReferralDraft,
    drafts.EntitiesDraft,
    drafts.OcrDraft,
]


class Answer(BaseModel):
    value: int


def completion(
    content: str | None = '{"value": 3}', finish_reason: str = "stop", usage: bool = True
) -> SimpleNamespace:
    return SimpleNamespace(
        model="solar-pro4-260806",
        usage=SimpleNamespace(prompt_tokens=120, completion_tokens=30) if usage else None,
        choices=[
            SimpleNamespace(finish_reason=finish_reason, message=SimpleNamespace(content=content))
        ],
    )


class StubEndpoint:
    """chat.completions / embeddings 대역. 받은 인자를 기록한다."""

    def __init__(self, outcome: Any) -> None:
        self.outcome = outcome
        self.calls: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        outcome = self.outcome(kwargs) if callable(self.outcome) else self.outcome
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class StubSDK:
    def __init__(self, chat: Any = None, embeddings: Any = None) -> None:
        self.completions = StubEndpoint(chat)
        self.embeddings = StubEndpoint(embeddings)
        self.chat = SimpleNamespace(completions=self.completions)
        self.timeouts: list[float] = []

    def with_options(self, *, timeout: float) -> "StubSDK":
        self.timeouts.append(timeout)
        return self


def make_client(chat: Any = None, embeddings: Any = None) -> tuple[UpstageLLMClient, StubSDK]:
    client = UpstageLLMClient(api_key="test-key", model="solar-pro4")
    sdk = StubSDK(chat, embeddings)
    client._chat = sdk  # type: ignore[assignment]
    client._extraction = sdk  # type: ignore[assignment]
    return client, sdk


def status_error(cls: type[openai.APIStatusError], status: int, code: str) -> openai.APIStatusError:
    request = httpx2.Request("POST", f"{BASE_URL}/chat/completions")
    return cls("error", response=httpx2.Response(status, request=request), body={"code": code})


def walk(node: Any, depth: int = 0) -> list[tuple[dict[str, Any], int]]:
    if isinstance(node, list):
        return [found for item in node for found in walk(item, depth)]
    if not isinstance(node, dict):
        return []
    below = depth + 1 if "properties" in node else depth
    return [(node, depth)] + [found for value in node.values() for found in walk(value, below)]


# --- 클라이언트 설정 --------------------------------------------------------


def test_clients_point_at_upstage_endpoints_and_do_not_retry_on_their_own() -> None:
    client = UpstageLLMClient(api_key="test-key", model="solar-pro4")

    assert str(client._chat.base_url) == "https://api.upstage.ai/v1/"
    assert str(client._extraction.base_url) == "https://api.upstage.ai/v1/information-extraction/"
    assert EXTRACTION_BASE_URL.startswith(BASE_URL)
    assert client._chat.max_retries == 0
    assert (client.provider, client.model) == ("upstage", "solar-pro4")


# --- 구조화 출력 스키마 -----------------------------------------------------


@pytest.mark.parametrize("model", DRAFT_MODELS, ids=[m.__name__ for m in DRAFT_MODELS])
def test_draft_schema_fits_upstage_structured_output_rules(model: type[BaseModel]) -> None:
    schema = strict_schema(model)
    nodes = walk(schema)

    assert schema["type"] == "object"
    for node, depth in nodes:
        if "properties" in node:
            assert node["required"] == list(node["properties"])  # 모든 속성이 required
            assert node["additionalProperties"] is False
        assert depth <= MAX_SCHEMA_DEPTH
    used_keywords = {key for node, _ in nodes for key in node if key in UNSUPPORTED_KEYWORDS}
    # 속성 이름으로 쓰인 것은 빼고 본다 (예: client_summary 안의 필드 이름)
    property_names = {name for node, _ in nodes for name in node.get("properties", {})}
    assert used_keywords - property_names == set()
    assert all(ref.startswith("#/$defs/") for ref in json.dumps(schema).split('"$ref": "')[1:])


def test_optional_field_becomes_a_type_list_with_null() -> None:
    schema = strict_schema(drafts.OcrDraft)

    assert schema["properties"]["name"]["type"] == ["string", "null"]
    assert schema["properties"]["confidence"]["type"] == ["number", "null"]
    assert "자격 소지자 이름" in schema["properties"]["name"]["description"]


def test_field_with_default_is_still_required() -> None:
    schema = strict_schema(drafts.TagDraft)

    assert schema["required"] == ["emotions", "distortions", "keywords"]


# --- 채팅(JSON 출력) --------------------------------------------------------


async def test_complete_json_sends_trd_temperature_and_exact_output_cap() -> None:
    client, sdk = make_client(chat=completion())

    result = await client.complete_json("system", "user", Answer, timeout=3.0, max_tokens=100)

    call = sdk.completions.calls[0]
    assert result.parsed.value == 3
    assert (result.tokens_in, result.tokens_out, result.model) == (120, 30, "solar-pro4-260806")
    assert sdk.timeouts == [3.0]
    assert call["model"] == "solar-pro4"
    assert 0.0 <= call["temperature"] <= 0.2  # TRD 16-4
    assert call["temperature"] == TEMPERATURE
    assert call["max_tokens"] == 100  # TRD 16-9 출력 상한 그대로
    assert "reasoning_effort" not in call  # 추론을 켜면 출력 상한 안에서 답이 잘린다
    assert call["messages"] == [
        {"role": "system", "content": "system"},
        {"role": "user", "content": "user"},
    ]
    assert call["response_format"] == {
        "type": "json_schema",
        "json_schema": {"name": "Answer", "strict": True, "schema": strict_schema(Answer)},
    }


@pytest.mark.parametrize(
    "response",
    [
        completion(finish_reason="length"),
        completion(content=None),
        completion(content=""),
        completion(content="not json"),
        completion(content='{"value": "비밀값"}'),
    ],
)
async def test_truncated_empty_or_invalid_output_is_reported_with_tokens(
    response: SimpleNamespace,
) -> None:
    client, _ = make_client(chat=response)

    with pytest.raises(LLMOutputInvalid) as caught:
        await client.complete_json("s", "u", Answer, timeout=3.0)

    assert caught.value.tokens == 150  # 실패한 시도도 일일 상한에 들어간다
    assert "비밀값" not in caught.value.detail


async def test_missing_usage_counts_as_zero_tokens() -> None:
    client, _ = make_client(chat=completion(usage=False))

    result = await client.complete_json("s", "u", Answer, timeout=3.0)

    assert (result.tokens_in, result.tokens_out) == (0, 0)


def connection_request() -> httpx2.Request:
    return httpx2.Request("POST", f"{BASE_URL}/chat/completions")


@pytest.mark.parametrize(
    ("raised", "expected", "retryable"),
    [
        (status_error(openai.RateLimitError, 429, "too_many_requests"), LLMRateLimited, None),
        (status_error(openai.RateLimitError, 429, "usage_limit_exceeded"), LLMProviderError, False),
        (
            status_error(openai.InternalServerError, 500, "internal_server_error"),
            LLMProviderError,
            True,
        ),
        (
            status_error(openai.BadRequestError, 400, "invalid_request_body"),
            LLMProviderError,
            False,
        ),
        (status_error(openai.AuthenticationError, 401, "invalid_api_key"), LLMProviderError, False),
        (openai.APITimeoutError(request=connection_request()), LLMTimeout, None),
        (openai.APIConnectionError(request=connection_request()), LLMProviderError, True),
    ],
)
async def test_sdk_errors_map_to_llm_errors(
    raised: Exception, expected: type[Exception], retryable: bool | None
) -> None:
    client, _ = make_client(chat=raised, embeddings=raised)

    with pytest.raises(expected) as caught:
        await client.complete_json("s", "u", Answer, timeout=3.0)
    with pytest.raises(expected):
        await client.embed(["태그"])

    if retryable is not None:
        assert caught.value.retryable is retryable  # type: ignore[attr-defined]


# --- 임베딩 ----------------------------------------------------------------


async def test_embed_batches_by_100_and_keeps_input_order() -> None:
    def respond(kwargs: dict[str, Any]) -> SimpleNamespace:
        # 일부러 뒤집어 돌려준다. index로 다시 맞춰야 한다.
        items = [
            SimpleNamespace(index=index, embedding=[float(text.removeprefix("t"))])
            for index, text in enumerate(kwargs["input"])
        ]
        return SimpleNamespace(data=list(reversed(items)))

    client, sdk = make_client(embeddings=respond)
    texts = [f"t{number}" for number in range(EMBED_BATCH_SIZE + 5)]

    vectors = await client.embed(texts)

    assert [len(call["input"]) for call in sdk.embeddings.calls] == [EMBED_BATCH_SIZE, 5]
    assert all(call["model"] == EMBEDDING_MODEL for call in sdk.embeddings.calls)
    assert vectors == [[float(number)] for number in range(EMBED_BATCH_SIZE + 5)]


# --- 서류 추출 --------------------------------------------------------------


@pytest.mark.parametrize("data", [PNG_BYTES, PDF_BYTES], ids=["png", "pdf"])
async def test_vision_json_follows_the_information_extract_request_shape(data: bytes) -> None:
    client, sdk = make_client(chat=completion())

    result = await client.vision_json("system", data, Answer, timeout=10.0)

    call = sdk.completions.calls[0]
    url = call["messages"][0]["content"][0]["image_url"]["url"]
    prefix = "data:application/octet-stream;base64,"
    assert result.parsed.value == 3
    assert call["model"] == EXTRACTION_MODEL
    assert [message["role"] for message in call["messages"]] == ["user"]
    assert call["messages"][0]["content"][0]["type"] == "image_url"
    assert url.startswith(prefix)
    assert base64.standard_b64decode(url.removeprefix(prefix)) == data
    assert call["response_format"] == {
        "type": "json_schema",
        "json_schema": {"name": "Answer", "schema": strict_schema(Answer)},
    }
    assert sdk.timeouts == [10.0]


async def test_vision_json_rejects_unknown_file_type_without_calling_the_api() -> None:
    client, sdk = make_client(chat=completion())

    with pytest.raises(LLMProviderError) as caught:
        await client.vision_json("system", b"plain text", Answer, timeout=10.0)

    assert caught.value.retryable is False
    assert sdk.completions.calls == []


# --- Redis 토큰 카운터 ------------------------------------------------------


class FakeRedis:
    def __init__(self, *, broken: bool = False) -> None:
        self.values: dict[str, int] = {}
        self.ttl: dict[str, int] = {}
        self.broken = broken

    def _check(self) -> None:
        if self.broken:
            raise ConnectionError("redis down")

    async def incrby(self, key: str, amount: int) -> int:
        self._check()
        self.values[key] = self.values.get(key, 0) + amount
        return self.values[key]

    async def expire(self, key: str, seconds: int) -> None:
        self._check()
        self.ttl[key] = seconds

    async def get(self, key: str) -> str | None:
        self._check()
        return str(self.values[key]) if key in self.values else None

    async def ping(self) -> bool:
        self._check()
        return True


async def test_redis_budget_counts_under_todays_kst_key() -> None:
    redis = FakeRedis()
    budget = RedisTokenBudget(redis, cap=1000)

    await budget.add(250)
    await budget.add(250)

    assert redis.values == {today_key(): 500}
    assert redis.ttl[today_key()] > 24 * 3600
    assert await budget.used_ratio() == 0.5
    assert await budget.exhausted() is False
    assert await budget.ping() is True


async def test_redis_budget_is_exhausted_at_cap() -> None:
    budget = RedisTokenBudget(FakeRedis(), cap=100)

    await budget.add(150)

    assert await budget.used_ratio() == 1.0
    assert await budget.exhausted() is True


async def test_redis_budget_keeps_counting_in_memory_when_redis_is_down() -> None:
    budget = RedisTokenBudget(FakeRedis(broken=True), cap=100)

    await budget.add(100)

    assert await budget.exhausted() is True
    assert await budget.ping() is False
