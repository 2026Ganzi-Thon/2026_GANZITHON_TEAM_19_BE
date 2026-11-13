"""Upstage(Solar) 공급자 클라이언트 (부록 H-03 결정).

Upstage API는 OpenAI 호환이라 TRD 3-3의 openai SDK에 base_url만 바꿔서 부른다.
요청 형태는 Upstage 공식 문서(console.upstage.ai/docs)의 예시를 따른다.
"""

import base64
from typing import Any

import openai
from pydantic import BaseModel, ValidationError

from app.llm.client import (
    LLMError,
    LLMOutputInvalid,
    LLMProviderError,
    LLMRateLimited,
    LLMResult,
    LLMTimeout,
    sniff_media_type,
    validation_summary,
)

BASE_URL = "https://api.upstage.ai/v1"
EXTRACTION_BASE_URL = "https://api.upstage.ai/v1/information-extraction"
EXTRACTION_MODEL = "information-extract"
# 키워드와 분야 태그는 둘 다 짧은 구라서 같은 모델로 임베딩한다 (같은 글이면 유사도 1).
EMBEDDING_MODEL = "solar-embedding-2-query"
EMBED_BATCH_SIZE = 100  # 요청 하나에 문자열 100개까지
TEMPERATURE = 0.1  # TRD 16-4: 온도 0~0.2
QUOTA_EXHAUSTED_CODE = "usage_limit_exceeded"
REQUEST_TIMEOUT_STATUS = 408
# 추론은 켜지 않는다 (reasoning_effort를 보내지 않으면 꺼진다). 추론 토큰이 출력 토큰에
# 들어가므로, 켜면 TRD 16-9의 출력 상한 안에서 답이 잘릴 수 있다.

_DROPPED_KEYS = frozenset({"title", "default"})
_NAME_MAPS = frozenset({"properties", "$defs"})  # 값이 '이름 → 스키마' 사전인 키
_NULLABLE_OPTIONS = 2


def strict_schema(model: type[BaseModel]) -> dict[str, Any]:
    """Pydantic 스키마를 Upstage 구조화 출력이 받는 형태로 바꾼다.

    모든 속성을 required에 넣고, 객체마다 additionalProperties=false를 두고,
    null 허용은 anyOf 대신 "type": ["string", "null"]로 쓴다.
    """
    converted: dict[str, Any] = _strict(model.model_json_schema())
    return converted


def _strict(node: Any) -> Any:
    if isinstance(node, list):
        return [_strict(item) for item in node]
    if not isinstance(node, dict):
        return node
    out: dict[str, Any] = {}
    for key, value in node.items():
        if key in _DROPPED_KEYS:
            continue
        if key in _NAME_MAPS:
            out[key] = {name: _strict(sub) for name, sub in value.items()}
        else:
            out[key] = _strict(value)
    nullable = _nullable_type(out.get("anyOf"))
    if nullable is not None:
        del out["anyOf"]
        out["type"] = nullable
    if "properties" in out:
        out["required"] = list(out["properties"])
        out["additionalProperties"] = False
    return out


def _nullable_type(options: Any) -> list[str] | None:
    """anyOf가 [단순 타입, null] 꼴이면 타입 목록으로 바꾼다."""
    if not isinstance(options, list) or len(options) != _NULLABLE_OPTIONS:
        return None
    if not all(isinstance(option, dict) and set(option) == {"type"} for option in options):
        return None
    types = [option["type"] for option in options]
    if "null" not in types or not all(isinstance(name, str) for name in types):
        return None
    return [name for name in types if name != "null"] + ["null"]


def _map_error(exc: openai.OpenAIError) -> LLMError:
    """SDK 오류를 공급자와 무관한 오류로 바꾼다. 가장 좁은 종류부터 본다."""
    if isinstance(exc, openai.RateLimitError):
        if exc.code == QUOTA_EXHAUSTED_CODE:
            return LLMProviderError("usage limit exceeded", retryable=False)
        return LLMRateLimited("rate limited")
    if isinstance(exc, openai.APITimeoutError):
        return LLMTimeout("provider timeout")
    if isinstance(exc, openai.APIConnectionError):
        return LLMProviderError("connection error")
    if isinstance(exc, openai.APIStatusError):
        retryable = exc.status_code >= 500 or exc.status_code == REQUEST_TIMEOUT_STATUS
        return LLMProviderError(f"status {exc.status_code}", retryable=retryable)
    return LLMProviderError(type(exc).__name__, retryable=False)


def _to_result[T: BaseModel](response: Any, schema: type[T], model: str) -> LLMResult[T]:
    usage = response.usage
    tokens_in = usage.prompt_tokens if usage else 0
    tokens_out = usage.completion_tokens if usage else 0
    used = tokens_in + tokens_out
    choice = response.choices[0] if response.choices else None
    content = choice.message.content if choice else None
    if choice is None or choice.finish_reason == "length" or not content:
        raise LLMOutputInvalid("output truncated or empty", tokens=used)
    try:
        parsed = schema.model_validate_json(content)
    except ValidationError as exc:
        raise LLMOutputInvalid(validation_summary(exc), tokens=used) from None
    return LLMResult(
        parsed=parsed, tokens_in=tokens_in, tokens_out=tokens_out, model=response.model or model
    )


class UpstageLLMClient:
    provider = "upstage"

    def __init__(self, api_key: str, model: str) -> None:
        self.model = model
        # 재시도와 백오프는 게이트웨이가 제한 시간 안에서 직접 한다.
        self._chat = openai.AsyncOpenAI(api_key=api_key, base_url=BASE_URL, max_retries=0)
        self._extraction = openai.AsyncOpenAI(
            api_key=api_key, base_url=EXTRACTION_BASE_URL, max_retries=0
        )

    async def aclose(self) -> None:
        """서버가 내려갈 때 연결을 닫는다."""
        await self._chat.close()
        await self._extraction.close()

    async def complete_json[T: BaseModel](
        self, system: str, user: str, schema: type[T], timeout: float, max_tokens: int = 1500
    ) -> LLMResult[T]:
        messages: Any = [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]
        response_format: Any = {
            "type": "json_schema",
            "json_schema": {
                "name": schema.__name__,
                "strict": True,
                "schema": strict_schema(schema),
            },
        }
        try:
            response = await self._chat.with_options(timeout=timeout).chat.completions.create(
                model=self.model,
                messages=messages,
                temperature=TEMPERATURE,
                max_tokens=max_tokens,
                response_format=response_format,
            )
        except openai.OpenAIError as exc:
            raise _map_error(exc) from None
        return _to_result(response, schema, self.model)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        try:
            for start in range(0, len(texts), EMBED_BATCH_SIZE):
                batch = texts[start : start + EMBED_BATCH_SIZE]
                response = await self._chat.embeddings.create(
                    model=EMBEDDING_MODEL, input=batch, encoding_format="float"
                )
                ordered = sorted(response.data, key=lambda item: item.index)
                vectors.extend(item.embedding for item in ordered)
        except openai.OpenAIError as exc:
            raise _map_error(exc) from None
        return vectors

    async def vision_json[T: BaseModel](
        self, system: str, image: bytes, schema: type[T], timeout: float
    ) -> LLMResult[T]:
        """서류에서 필드를 읽는다 (Information Extract API).

        이 API는 스키마의 필드 이름과 설명을 보고 값을 뽑는다. 문서 예시에 system
        메시지가 없어 보내지 않고, 지시는 스키마의 description에 둔다.
        """
        if sniff_media_type(image) is None:
            raise LLMProviderError("unsupported file type", retryable=False)
        encoded = base64.standard_b64encode(image).decode("ascii")
        messages: Any = [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:application/octet-stream;base64,{encoded}"},
                    }
                ],
            }
        ]
        response_format: Any = {
            "type": "json_schema",
            "json_schema": {"name": schema.__name__, "schema": strict_schema(schema)},
        }
        try:
            response = await self._extraction.with_options(timeout=timeout).chat.completions.create(
                model=EXTRACTION_MODEL, messages=messages, response_format=response_format
            )
        except openai.OpenAIError as exc:
            raise _map_error(exc) from None
        return _to_result(response, schema, EXTRACTION_MODEL)
