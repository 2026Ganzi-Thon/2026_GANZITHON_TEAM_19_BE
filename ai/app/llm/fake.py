"""LLM_PROVIDER=fake 일 때 쓰는 고정 응답 클라이언트 (TRD 16-6).

staging·로컬·CI·E2E가 실 LLM 없이 돌도록, 입력에서 문장을 그대로 골라
근거 인용 검증을 통과하는 응답을 만든다.
"""

import asyncio
import json
import math
import re
import zlib
from collections.abc import Callable
from typing import Any

from pydantic import BaseModel

from app.llm.client import LLMResult
from app.resources import TagDictionary

EMBED_DIM = 256
FAKE_MODEL = "fake-1"
FAKE_TOKENS_OUT = 50
_USER_TEXT = re.compile(r"<user_text>\n?(.*?)\n?</user_text>", re.DOTALL)
_CLIENT_PREFIX = "내담자: "
_NAME_BEFORE_TITLE = re.compile(r"([가-힣]{2,4})(?:씨|님)")
_CATASTROPHE_WORDS = ("망", "끝", "최악", "다 틀렸")
_QUOTE_LIMIT = 120


def hash_embed(texts: list[str], dim: int = EMBED_DIM) -> list[list[float]]:
    """글자 1·2그램을 해시한 어휘 벡터. 임베딩 API가 없을 때의 대체값으로도 쓴다."""
    return [_hash_vector(text, dim) for text in texts]


def _hash_vector(text: str, dim: int) -> list[float]:
    compact = "".join(text.split()).lower()
    grams = list(compact) + [compact[i : i + 2] for i in range(len(compact) - 1)]
    vector = [0.0] * dim
    for gram in grams:
        vector[zlib.crc32(gram.encode("utf-8")) % dim] += 1.0
    norm = math.sqrt(sum(v * v for v in vector))
    return [v / norm for v in vector] if norm else vector


def _user_text(user: str) -> str:
    matches = _USER_TEXT.findall(user)
    return matches[-1] if matches else user


class FakeLLMClient:
    provider = "fake"
    model = FAKE_MODEL

    def __init__(self, dictionary: TagDictionary, latency_ms: int = 0) -> None:
        self._dictionary = dictionary
        self._latency = latency_ms / 1000
        self._builders: dict[str, Callable[[str], dict[str, Any]]] = {
            "TagDraft": self._tags,
            "RiskDraft": self._risk,
            "SessionDraft": self._session,
            "ReferralDraft": self._referral,
            "EntitiesDraft": self._entities,
            "OcrDraft": self._ocr,
        }

    async def complete_json[T: BaseModel](
        self, system: str, user: str, schema: type[T], timeout: float, max_tokens: int = 1500
    ) -> LLMResult[T]:
        return await self._respond(schema, _user_text(user), tokens_in=len(user) // 2)

    async def vision_json[T: BaseModel](
        self, system: str, image: bytes, schema: type[T], timeout: float
    ) -> LLMResult[T]:
        return await self._respond(schema, "", tokens_in=1000)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return hash_embed(texts)

    async def _respond[T: BaseModel](
        self, schema: type[T], text: str, tokens_in: int
    ) -> LLMResult[T]:
        if self._latency:
            await asyncio.sleep(self._latency)
        parsed = schema.model_validate(self._builders[schema.__name__](text))
        return LLMResult(
            parsed=parsed, tokens_in=tokens_in, tokens_out=FAKE_TOKENS_OUT, model=self.model
        )

    def _tags(self, text: str) -> dict[str, Any]:
        emotions = [e for e in self._dictionary.emotions if e in text][:2] or ["불안"]
        distortions = ["파국화"] if any(w in text for w in _CATASTROPHE_WORDS) else []
        keywords = [
            tag for tag in self._dictionary.specialties if any(word in text for word in tag.split())
        ][:3]
        return {"emotions": emotions, "distortions": distortions, "keywords": keywords}

    def _risk(self, text: str) -> dict[str, Any]:
        # 규칙 필터가 항상 먼저 돌므로 fake는 낮게 답한다. 최종 값은 max(규칙, LLM)이다.
        return {"level": "none", "score": 0.05, "evidence_span": ""}

    def _session(self, text: str) -> dict[str, Any]:
        client_lines = [
            line[len(_CLIENT_PREFIX) :].strip()
            for line in text.splitlines()
            if line.startswith(_CLIENT_PREFIX)
        ]
        quotes = [line[:_QUOTE_LIMIT] for line in client_lines if line][:2]
        distortions = [{"name": "파국화", "evidence": quotes[0]}] if quotes else []
        return {
            "chief_complaint": "최근 겪는 어려움과 그때 드는 생각을 이야기했습니다.",
            "distortions": distortions,
            "triggers": ["부담이 커지는 상황"],
            "defenses": ["혼자 견디려는 모습"],
            "risk_level": "none",
            "client_summary": {
                "chief_complaint": "요즘 마음을 무겁게 하는 일을 함께 살펴봤어요.",
                "thinking_patterns": "일이 가장 나쁜 쪽으로 흘러갈 거라고 느끼는 순간이 있었어요.",
                "triggers": "부담이 커질 때 마음이 더 힘들어지는 것 같아요.",
                "coping": "혼자 버티기보다 조금씩 나눠 보는 연습을 해 볼 수 있어요.",
            },
            "digest": {
                "issues": ["최근의 어려움", "부담감"],
                "key_quotes": quotes,
                "mood_shift": "이야기를 하면서 긴장이 조금 풀렸습니다.",
                "trainee_interventions": ["감정 반영", "상황 구체화 질문"],
            },
        }

    def _referral(self, text: str) -> dict[str, Any]:
        data = json.loads(text)
        digests = data.get("digests", [])
        timeline = [
            {
                "session_no": entry["session_no"],
                "key_points": entry["digest"].get("issues", [])[:2] or ["회기 요약"],
                "client_line": f"{entry['session_no']}회기에 나눈 이야기를 정리했어요.",
            }
            for entry in digests
        ]
        distortions = [
            {
                "name": "파국화",
                "evidence": entry["digest"]["key_quotes"][0],
                "session_no": entry["session_no"],
            }
            for entry in digests
            if entry["digest"].get("key_quotes")
        ][:2]
        return {
            "chief_complaint": "여러 회기에 걸쳐 이어진 어려움을 이야기했습니다.",
            "timeline": timeline,
            "distortions": distortions,
            "triggers": ["부담이 커지는 상황"],
            "defenses": ["혼자 견디려는 모습"],
            "progress": "회기가 이어지면서 자기 마음을 말로 옮기는 일이 늘었습니다.",
            "focus_suggestions": ["첫 회기에 최근 가장 힘들었던 순간을 함께 살펴보기"],
            "client_summary": {
                "chief_complaint": "그동안 나눈 이야기의 흐름을 정리했어요.",
                "thinking_patterns": "힘들 때 가장 나쁜 결과를 먼저 떠올리는 순간이 있었어요.",
                "triggers": "부담이 커질 때 마음이 더 힘들어지는 것 같아요.",
                "coping": "전문 상담에서 이 부분을 조금 더 깊이 다뤄 볼 수 있어요.",
            },
        }

    def _entities(self, text: str) -> dict[str, Any]:
        names = sorted(set(_NAME_BEFORE_TITLE.findall(text)))
        return {"entities": [{"type": "이름", "text": name} for name in names]}

    def _ocr(self, _text: str) -> dict[str, Any]:
        return {
            "name": "홍길동",
            "cert_name": "상담심리사 2급",
            "cert_number": "2026-0001",
            "issued_on": "2026-03-02",
            "issuer": "한국상담심리학회",
            "confidence": 0.9,
        }
