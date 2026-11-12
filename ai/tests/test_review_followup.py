"""두 번째 코드 리뷰에서 나온 지적의 회귀 테스트."""

import asyncio
import time
from datetime import datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx2
import openai
import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app import errors
from app.functions.ocr import parse_date, read_certificate
from app.functions.referral_report import generate_referral_report
from app.functions.session_report import MAX_DISTORTION_ITEMS, generate_session_report
from app.llm.breaker import CircuitBreaker, Permit
from app.llm.budget import KST, MemoryTokenBudget
from app.llm.client import LLMProviderError
from app.llm.gateway import LLMGateway
from app.llm.limits import ConcurrencyLimits
from app.llm.upstage_client import UpstageLLMClient, _map_error
from app.resources import AI_ROOT
from app.safety.evidence import QuoteIndex
from app.schemas import Intake, SessionReportRequest, Utterance
from tests.conftest import HEADERS, PNG_BYTES, ScriptedLLM, make_services
from tests.test_reports import REAL_QUOTE, referral_draft, referral_request, session_draft

LOOKUP_BUDGET_SECONDS = 2.0


class SimpleAnswer(BaseModel):
    value: int


class SpyBreaker(CircuitBreaker):
    def __init__(self) -> None:
        super().__init__()
        self.outcomes: list[bool | None] = []

    def record(self, permit: Permit, ok: bool | None) -> None:
        self.outcomes.append(ok)
        super().record(permit, ok)


class BlockingLLM(ScriptedLLM):
    def __init__(self, *outcomes: Any) -> None:
        super().__init__(*outcomes)
        self.release = asyncio.Event()

    async def complete_json(self, *args: Any, **kwargs: Any) -> Any:
        await self.release.wait()
        return await super().complete_json(*args, **kwargs)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [[1.0] for _ in texts]


def gateway_for(llm: Any, breaker: CircuitBreaker, cap: int = 1_000_000) -> LLMGateway:
    return LLMGateway(llm, MemoryTokenBudget(cap), breaker, ConcurrencyLimits(risk=1, general=1))


async def chat(gateway: LLMGateway, timeout: float) -> Any:
    return await gateway.complete(
        system="s", user="u", schema=SimpleAnswer, timeout=timeout, max_tokens=10
    )


# --- CI, 계약 문서 ----------------------------------------------------------


def test_ci_reruns_when_the_breaking_label_is_added_or_removed() -> None:
    workflow = (AI_ROOT.parent / ".github" / "workflows" / "ci-ai.yml").read_text(encoding="utf-8")

    assert "types: [opened, synchronize, reopened, labeled, unlabeled]" in workflow


def test_contract_document_is_not_served_over_http(client: TestClient) -> None:
    assert client.get("/openapi.json").status_code == 404
    assert client.get("/openapi.json", headers=HEADERS).status_code == 404
    assert client.get("/docs").status_code == 404


# --- 게이트웨이 -------------------------------------------------------------


async def test_call_that_got_its_lane_too_late_is_not_sent_or_counted() -> None:
    breaker = SpyBreaker()
    llm = BlockingLLM({"value": 1})
    gateway = gateway_for(llm, breaker)
    first = asyncio.create_task(chat(gateway, timeout=5.0))
    await asyncio.sleep(0.01)
    late = asyncio.create_task(chat(gateway, timeout=0.3))
    await asyncio.sleep(0.2)

    llm.release.set()  # 첫 호출이 끝나고, 늦은 호출은 0.1초쯤 남기고 차선을 얻는다
    await first
    with pytest.raises(errors.AIError) as caught:
        await late

    assert caught.value.code == "AI_TIMEOUT"
    assert breaker.outcomes == [True, None]  # 공급사 실패로 세지 않는다
    assert len(llm.prompts) == 1  # 늦은 호출은 공급사에 보내지 않았다


async def test_embeddings_do_not_wait_behind_long_report_calls() -> None:
    llm = BlockingLLM({"value": 1})
    gateway = gateway_for(llm, CircuitBreaker())
    report = asyncio.create_task(chat(gateway, timeout=5.0))  # 하나뿐인 차선을 잡고 있다
    await asyncio.sleep(0.01)

    vectors = await gateway.embed(["태그"], timeout=0.5)
    llm.release.set()
    await report

    assert vectors == [[1.0]]


async def test_embedding_text_counts_toward_the_daily_cap() -> None:
    gateway = gateway_for(BlockingLLM({"value": 1}), CircuitBreaker(), cap=10)

    await gateway.embed(["가나다", "라마"], timeout=1.0)

    assert await gateway.budget.used_ratio() == 0.5  # 글자 수 5로 어림


# --- 근거 인용 --------------------------------------------------------------


def test_quote_lookup_returns_the_source_spelling() -> None:
    index = QuoteIndex(["상담자 말", "저는  뭘 해도\n안 될 것 같아요. 정말로요."])

    assert index.locate("뭘 해도 안 될 것 같아요") == (1, "뭘 해도\n안 될 것 같아요")
    assert index.locate("없는 문장입니다") is None
    assert index.verbatim(["정말로요", "정말로요.", "없는 문장"], 6) == ["정말로요", "정말로요."]


def test_quote_lookup_stays_fast_on_a_long_repetitive_transcript() -> None:
    index = QuoteIndex(["네 " * 150_000])
    started = time.perf_counter()

    found = [index.locate("네 네 네 네 네 아니요") for _ in range(10)]

    assert found == [None] * 10
    assert time.perf_counter() - started < LOOKUP_BUDGET_SECONDS


async def test_session_report_checks_only_the_first_few_llm_items() -> None:
    many = [{"name": "과잉일반화", "evidence": REAL_QUOTE} for _ in range(50)]
    services = make_services(llm=ScriptedLLM(session_draft(distortions=many)))
    request = SessionReportRequest(
        transcript=[Utterance(role="client", text=REAL_QUOTE)], intake=Intake(category="self")
    )

    report = await generate_session_report(request, services)

    assert len(report.distortions) == MAX_DISTORTION_ITEMS


async def test_referral_rejects_empty_evidence_even_if_a_digest_has_an_empty_quote() -> None:
    request = referral_request()
    request.digests[0].digest.key_quotes.append("")
    draft = referral_draft(distortions=[{"name": "파국화", "evidence": "", "session_no": 1}])
    services = make_services(llm=ScriptedLLM(draft))

    report = await generate_referral_report(request, services)

    assert report.distortions == []


# --- 서류 OCR ---------------------------------------------------------------


@pytest.mark.parametrize(
    ("draft", "expected"),
    [
        ({"name": None, "cert_name": None, "issuer": None}, 0.0),  # 아무것도 못 읽음
        ({"name": "홍길동", "cert_name": "상담심리사 2급", "issuer": "한국상담심리학회"}, 0.6),
    ],
)
async def test_ocr_confidence_cannot_exceed_the_share_of_fields_read(
    draft: dict[str, Any], expected: float
) -> None:
    reply = {**draft, "cert_number": None, "issued_on": None, "confidence": 0.9}
    services = make_services(llm=ScriptedLLM(reply))

    response = await read_certificate(PNG_BYTES, services)

    assert response.confidence == expected


def test_issue_date_is_compared_in_korean_time() -> None:
    today = datetime.now(KST).date()

    assert parse_date(today.isoformat()) == today
    assert parse_date((today + timedelta(days=1)).isoformat()) is None


# --- Upstage 클라이언트 -----------------------------------------------------


def test_request_timeout_status_is_worth_retrying() -> None:
    request = httpx2.Request("POST", "https://api.upstage.ai/v1/chat/completions")
    error = openai.APIStatusError(
        "timeout", response=httpx2.Response(408, request=request), body=None
    )

    mapped = _map_error(error)

    assert isinstance(mapped, LLMProviderError)
    assert mapped.retryable is True


async def test_embeddings_are_requested_as_plain_floats() -> None:
    calls: list[dict[str, Any]] = []

    async def create(**kwargs: Any) -> SimpleNamespace:
        calls.append(kwargs)
        return SimpleNamespace(data=[SimpleNamespace(index=0, embedding=[0.5])])

    client = UpstageLLMClient(api_key="test-key", model="solar-pro4")
    client._chat = SimpleNamespace(embeddings=SimpleNamespace(create=create))  # type: ignore[assignment]

    assert await client.embed(["태그"]) == [[0.5]]
    assert calls[0]["encoding_format"] == "float"
