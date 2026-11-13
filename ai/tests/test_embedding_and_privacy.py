"""점검에서 나온 문제의 회귀 테스트: 근거 문장의 개인정보, 임베딩 경로, 기동 설정."""

from typing import Any

import pytest
from fastapi.testclient import TestClient

from app import errors
from app.functions.masking import Replacement, to_masked_offset
from app.functions.session_report import generate_session_report
from app.llm.breaker import CircuitBreaker
from app.llm.budget import MemoryTokenBudget
from app.llm.client import LLMProviderError, LLMRateLimited, LLMTimeout, get_llm
from app.llm.gateway import LLMGateway
from app.llm.limits import ConcurrencyLimits
from app.llm.upstage_client import UpstageLLMClient
from app.main import create_app
from app.schemas import Intake, SessionReportRequest, Utterance
from app.services import Services
from tests.conftest import HEADERS, ScriptedLLM, make_services, make_settings
from tests.test_reports import session_draft

MATCH_REQUEST = {
    "keywords": ["취업 스트레스"],
    "category": "career",
    "candidates": [
        {"id": "p1", "tags": ["청년 진로", "가족 관계"]},
        {"id": "p2", "tags": ["임의로 넣은 태그", "010-1234-5678"]},
    ],
}


class EmbeddingLLM(ScriptedLLM):
    """임베딩 호출을 기록하고, 정해 둔 결과나 예외를 돌려준다."""

    def __init__(self, outcome: Any = None) -> None:
        super().__init__({})
        self.outcome = outcome
        self.embedded: list[list[str]] = []
        self.closed = False

    async def embed(self, texts: list[str]) -> list[list[float]]:
        self.embedded.append(texts)
        if isinstance(self.outcome, Exception):
            raise self.outcome
        if self.outcome is not None:
            return self.outcome
        return [[1.0, float(index)] for index in range(len(texts))]

    async def aclose(self) -> None:
        self.closed = True


# --- 근거 문장의 개인정보 ---------------------------------------------------


@pytest.mark.parametrize("use_llm", [True, False])
def test_risk_evidence_hides_contact_info(client: TestClient, use_llm: bool) -> None:
    response = client.post(
        "/v1/risk/assess",
        json={"text": "요즘은 그냥 죽고 싶어요 010-1234-5678", "use_llm": use_llm},
        headers=HEADERS,
    )

    body = response.json()
    assert body["level"] == "high"
    assert body["evidence"] == "요즘은 그냥 죽고 싶어요 [연락처1]"
    assert "010-1234-5678" not in response.text


async def test_session_report_risk_evidence_uses_the_same_tokens_as_the_transcript() -> None:
    services = make_services(llm=ScriptedLLM(session_draft(distortions=[])))
    request = SessionReportRequest(
        transcript=[
            Utterance(role="client", text="친구 번호는 010-9999-0000이에요."),
            Utterance(role="client", text="제 번호 010-1111-2222로 연락 주세요, 죽고 싶어요."),
        ],
        intake=Intake(category="self"),
    )

    report = await generate_session_report(request, services)

    assert report.red_flag.level == "high"
    # 대화에서 두 번째로 나온 번호라서 근거 문장에서도 [연락처2]다.
    assert report.red_flag.evidence == "제 번호 [연락처2]로 연락 주세요, 죽고 싶어요."


async def test_session_report_rules_run_on_the_original_text_not_the_masked_one() -> None:
    # 링크 안에 든 위험 표현은 마스킹하면 [링크1]로 사라진다. 규칙은 원문을 봐야 한다.
    services = make_services(llm=ScriptedLLM(session_draft(distortions=[])))
    request = SessionReportRequest(
        transcript=[Utterance(role="client", text="https://example.com/자살 이 글을 계속 봐요")],
        intake=Intake(category="self"),
    )

    report = await generate_session_report(request, services)

    assert report.red_flag.level == "high"
    assert report.red_flag.evidence == "[링크1] 이 글을 계속 봐요"


def test_risk_evidence_hides_an_email_even_though_it_contains_periods(client: TestClient) -> None:
    # 문장을 마침표로 먼저 자르면 이메일이 조각나서 가려지지 않는다.
    response = client.post(
        "/v1/risk/assess",
        json={"text": "죽고 싶어요 min.su@example.com 으로 연락 주세요", "use_llm": False},
        headers=HEADERS,
    )

    assert response.json()["evidence"] == "죽고 싶어요 [이메일1] 으로 연락 주세요"
    assert "example" not in response.text


@pytest.mark.parametrize(
    ("position", "is_end", "expected"),
    [
        (0, False, 0),  # 가려진 구간 앞
        (4, False, 4),  # 구간이 시작하는 자리
        (6, False, 4),  # 구간 안의 시작 위치 → 토큰의 시작
        (6, True, 9),  # 구간 안의 끝 위치 → 토큰의 끝
        (4, True, 4),  # 구간 바로 앞에서 끝나는 일치
        (12, False, 9),  # 구간 뒤는 길이 차이만큼 당겨진다
    ],
)
def test_original_positions_map_onto_the_masked_text(
    position: int, is_end: bool, expected: int
) -> None:
    replaced = [Replacement(start=4, end=12, masked_start=4, masked_end=9)]

    assert to_masked_offset(position, replaced, is_end=is_end) == expected


# --- 임베딩 경로 (AI-02) ----------------------------------------------------


def post_match(services: Services) -> Any:
    with TestClient(create_app(services)) as client:
        return client.post("/v1/match/specialty-scores", json=MATCH_REQUEST, headers=HEADERS)


@pytest.mark.parametrize(
    ("raised", "status", "code"),
    [
        (LLMProviderError("status 500"), 502, "AI_PROVIDER_ERROR"),
        (LLMRateLimited("429"), 429, "AI_RATE_LIMITED"),
        (LLMTimeout("slow"), 504, "AI_TIMEOUT"),
        (RuntimeError("boom"), 502, "AI_PROVIDER_ERROR"),
    ],
)
def test_embedding_failure_is_an_error_so_the_backend_falls_back(
    raised: Exception, status: int, code: str
) -> None:
    response = post_match(make_services(llm=EmbeddingLLM(raised)))

    assert response.status_code == status
    assert response.json()["error"]["code"] == code
    assert "scores" not in response.json()


def test_embedding_respects_the_daily_token_cap() -> None:
    llm = EmbeddingLLM()
    services = make_services(llm=llm, llm_daily_token_cap=10)
    with TestClient(create_app(services)) as client:
        client.post(
            "/v1/intake/tags", json={"category": "self", "text": "불안해요"}, headers=HEADERS
        )
        response = client.post("/v1/match/specialty-scores", json=MATCH_REQUEST, headers=HEADERS)

    assert response.status_code == 429
    assert response.json()["error"]["code"] == "AI_BUDGET_EXCEEDED"
    assert llm.embedded == []


def test_only_dictionary_tags_are_cached_and_embedded_text_is_masked() -> None:
    llm = EmbeddingLLM()
    services = make_services(llm=llm)

    first = post_match(services)
    second = post_match(services)

    assert first.status_code == second.status_code == 200
    assert set(services.tag_vectors) == {"청년 진로", "가족 관계"}  # 임의 태그는 캐시하지 않는다
    assert all("010-1234-5678" not in text for batch in llm.embedded for text in batch)
    assert "[연락처1]" in llm.embedded[0]
    assert len(llm.embedded[1]) == 3  # 질의 + 캐시에 없는 태그 2개
    assert first.json()["meta"]["fallback"] is False


async def test_gateway_rejects_an_embedding_reply_with_the_wrong_count() -> None:
    gateway = LLMGateway(
        EmbeddingLLM([[1.0]]), MemoryTokenBudget(1000), CircuitBreaker(), ConcurrencyLimits()
    )

    with pytest.raises(errors.AIError) as caught:
        await gateway.embed(["하나", "둘"], timeout=3.0)

    assert caught.value.code == "AI_OUTPUT_INVALID"


# --- 기동 설정 --------------------------------------------------------------


def test_real_provider_needs_redis_for_the_daily_cap() -> None:
    with pytest.raises(ValueError, match="REDIS_URL"):
        make_settings(llm_provider="upstage", llm_api_key="test-key", llm_model="solar-pro4")


def test_upstage_settings_build_the_upstage_client() -> None:
    settings = make_settings(
        llm_provider="upstage",
        llm_api_key="test-key",
        llm_model="solar-pro4",
        redis_url="redis://localhost:6379/1",
    )

    llm = get_llm(settings)

    assert isinstance(llm, UpstageLLMClient)
    assert (llm.provider, llm.model) == ("upstage", "solar-pro4")
    assert "test-key" not in repr(settings)


async def test_closing_services_closes_the_llm_connections() -> None:
    llm = EmbeddingLLM()
    services = make_services(llm=llm)

    await services.close()

    assert llm.closed is True
