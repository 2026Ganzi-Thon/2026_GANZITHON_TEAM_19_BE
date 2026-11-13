"""코드 리뷰에서 나온 결함의 회귀 테스트.

정규식 ReDoS, 게이트웨이·서킷의 예외 경로, 본문 크기 제한 우회, 인증 순서,
로그 누출, 입력 검증을 본다.
"""

import asyncio
import json
import re
import time
from collections.abc import Iterator
from typing import Any

import pytest
import yaml
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app import errors
from app.demo_cache.store import save_entry
from app.functions.referral_report import build_red_flag
from app.functions.session_report import (
    HEAD_UTTERANCE_CHARS,
    MAX_TRANSCRIPT_TOKENS,
    condense,
    estimate_tokens,
    generate_session_report,
)
from app.functions.tagging import tag_intake
from app.llm.breaker import MIN_CALLS, OPEN_SECONDS, CircuitBreaker, Permit
from app.llm.budget import MemoryTokenBudget
from app.llm.client import LLMOutputInvalid, LLMProviderError
from app.llm.gateway import LLMGateway
from app.llm.limits import ConcurrencyLimits
from app.main import create_app
from app.resources import AI_ROOT
from app.safety.output_filter import DIAGNOSIS_PATTERN
from app.schemas import (
    MAX_UTTERANCE_CHARS,
    Digest,
    DigestEntry,
    Intake,
    Meta,
    ReferralReportRequest,
    RiskHistoryItem,
    SessionReportRequest,
    TagIntakeRequest,
    Utterance,
)
from app.text import normalize
from tests.conftest import HEADERS, ScriptedLLM, make_services
from tests.test_reports import REAL_QUOTE, session_draft

REGEX_BUDGET_SECONDS = 1.0
SECRET = "비밀문장-로그금지"


class Answer(BaseModel):
    value: int


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


class SpyBreaker(CircuitBreaker):
    def __init__(self) -> None:
        super().__init__()
        self.outcomes: list[bool | None] = []

    def record(self, permit: Permit, ok: bool | None) -> None:
        self.outcomes.append(ok)
        super().record(permit, ok)


class BlockingLLM(ScriptedLLM):
    """release가 풀릴 때까지 응답하지 않는 LLM."""

    def __init__(self, *outcomes: Any) -> None:
        super().__init__(*outcomes)
        self.release = asyncio.Event()

    async def complete_json(self, *args: Any, **kwargs: Any) -> Any:
        await self.release.wait()
        return await super().complete_json(*args, **kwargs)


def gateway_for(llm: Any, breaker: CircuitBreaker | None = None, lanes: int = 4) -> LLMGateway:
    return LLMGateway(
        llm,
        MemoryTokenBudget(1_000_000),
        breaker or CircuitBreaker(),
        ConcurrencyLimits(risk=lanes, general=lanes),
    )


async def call(gateway: LLMGateway, timeout: float = 5.0) -> Any:
    return await gateway.complete(
        system="s", user="u", schema=Answer, timeout=timeout, max_tokens=10
    )


# --- 정규식 ---------------------------------------------------------------


def all_patterns() -> list[str]:
    lexicon = yaml.safe_load((AI_ROOT / "risk" / "risk_lexicon.yaml").read_text(encoding="utf-8"))
    pii = yaml.safe_load((AI_ROOT / "masking" / "pii_patterns.yaml").read_text(encoding="utf-8"))
    return [
        *(p for category in lexicon["categories"] for p in category["patterns"]),
        *lexicon["dampeners"]["after"],
        *lexicon["dampeners"]["before"],
        *(p["pattern"] for p in pii["patterns"]),
    ]


ADVERSARIAL_INPUTS = [
    "약" * 20_000,
    "카톡" + " " * 20_000 + "!",
    "a" * 200_000,
    "a@" + "b." * 20_000 + "1",
    ("죽고" + " " * 50) * 2_000,
    "수면제 a " * 5_000,
]


@pytest.mark.parametrize("text", ADVERSARIAL_INPUTS, ids=range(len(ADVERSARIAL_INPUTS)))
def test_no_shared_pattern_blows_up_on_long_input(text: str) -> None:
    started = time.perf_counter()
    for pattern in all_patterns():
        list(re.compile(pattern, re.ASCII).finditer(text))

    assert time.perf_counter() - started < REGEX_BUDGET_SECONDS


def test_shared_patterns_have_no_unbounded_whitespace_quantifier() -> None:
    offenders = [p for p in all_patterns() if re.search(r"\\[sS][*+]", p)]

    assert offenders == []


def test_output_filter_pattern_is_fast_on_long_text() -> None:
    started = time.perf_counter()
    DIAGNOSIS_PATTERN.search("가" * 50_000)

    assert time.perf_counter() - started < REGEX_BUDGET_SECONDS


def test_normalize_folds_fullwidth_spaces_and_zero_width_but_keeps_jamo() -> None:
    assert normalize("０１０－１２３４") == "010-1234"
    assert normalize("죽고　싶​어요") == "죽고 싶어요"
    assert normalize("ㅋㅋㅋ ㅠㅠ") == "ㅋㅋㅋ ㅠㅠ"
    assert normalize("한") == "한"  # 자모로 풀린 한글은 합친다


# --- 게이트웨이·서킷 -------------------------------------------------------


async def test_unexpected_llm_exception_becomes_provider_error() -> None:
    gateway = gateway_for(ScriptedLLM(RuntimeError(SECRET)))

    with pytest.raises(errors.AIError) as caught:
        await call(gateway)

    assert caught.value.code == "AI_PROVIDER_ERROR"
    assert SECRET not in caught.value.message


async def test_probe_slot_is_released_when_probe_call_raises_unexpectedly() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(clock)
    for _ in range(MIN_CALLS):
        permit = breaker.allow()
        assert permit is not None
        breaker.record(permit, False)
    clock.now += OPEN_SECONDS
    gateway = gateway_for(ScriptedLLM(RuntimeError("boom")), breaker)

    with pytest.raises(errors.AIError):
        await call(gateway)  # 시험 호출이 예상 밖 오류로 끝남

    clock.now += OPEN_SECONDS
    assert breaker.allow() is not None  # 영영 막히지 않고 다시 시험할 수 있다


async def test_cancelled_call_returns_its_lane_and_permit() -> None:
    breaker = SpyBreaker()
    llm = BlockingLLM({"value": 1})
    gateway = gateway_for(llm, breaker, lanes=1)
    task = asyncio.create_task(call(gateway))
    await asyncio.sleep(0.01)

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    llm.release.set()

    assert breaker.outcomes == [None]
    assert (await call(gateway)).parsed.value == 1  # 차선이 풀려 다음 호출이 지나간다


async def test_time_spent_waiting_in_queue_is_not_a_provider_failure() -> None:
    breaker = SpyBreaker()
    llm = BlockingLLM({"value": 1})
    gateway = gateway_for(llm, breaker, lanes=1)
    first = asyncio.create_task(call(gateway))
    await asyncio.sleep(0.01)

    with pytest.raises(errors.AIError) as caught:
        await call(gateway, timeout=0.05)  # 차선이 차 있어 기다리다 끝남
    llm.release.set()
    await first

    assert caught.value.code == "AI_TIMEOUT"
    assert breaker.outcomes == [None, True]


async def test_provider_rejection_stays_retryable_per_trd_but_is_not_an_outage() -> None:
    breaker = SpyBreaker()
    gateway = gateway_for(ScriptedLLM(LLMProviderError("400", retryable=False)), breaker)

    with pytest.raises(errors.AIError) as caught:
        await call(gateway)

    assert (caught.value.code, caught.value.retryable) == ("AI_PROVIDER_ERROR", True)
    assert breaker.outcomes == [None]


async def test_invalid_output_does_not_count_toward_breaker() -> None:
    breaker = SpyBreaker()
    gateway = gateway_for(ScriptedLLM(LLMOutputInvalid("bad")), breaker)

    with pytest.raises(errors.AIError):
        await call(gateway)

    assert breaker.outcomes == [None]


async def test_tokens_of_failed_attempts_count_toward_daily_cap() -> None:
    llm = ScriptedLLM(LLMOutputInvalid("bad", tokens=500), {"value": 1})
    gateway = LLMGateway(llm, MemoryTokenBudget(1240), CircuitBreaker(), ConcurrencyLimits())

    await call(gateway)  # 실패한 시도 500 + 성공한 시도 120

    assert await gateway.budget.used_ratio() == 0.5


# --- HTTP 경계 -------------------------------------------------------------


def test_streamed_body_over_limit_gets_413_envelope(client: TestClient) -> None:
    def chunks() -> Iterator[bytes]:
        yield b'{"text": "'
        for _ in range(12):
            yield b"a" * 100_000
        yield b'"}'

    response = client.post("/v1/mask", content=chunks(), headers=HEADERS)

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "AI_PAYLOAD_TOO_LARGE"


def test_token_is_checked_before_the_body_is_validated(client: TestClient) -> None:
    response = client.post("/v1/risk/assess", content=b"{not json")

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "AI_UNAUTHORIZED"


def test_unexpected_error_returns_500_envelope_without_leaking_the_message(
    capsys: pytest.CaptureFixture[str],
) -> None:
    services = make_services()

    def boom(*_args: Any, **_kwargs: Any) -> str:
        raise RuntimeError(SECRET)

    services.masker.mask = boom  # type: ignore[method-assign]
    with TestClient(create_app(services)) as client:
        response = client.post("/v1/mask", json={"text": "안녕하세요"}, headers=HEADERS)

    captured = capsys.readouterr()
    assert response.status_code == 500
    assert response.json()["error"] == {
        "code": "AI_INTERNAL",
        "message": "internal error",
        "retryable": False,
    }
    assert '"error_type": "RuntimeError"' in captured.out
    assert SECRET not in captured.out + captured.err


def test_risk_answers_with_rule_result_when_llm_raises_unexpected_error() -> None:
    services = make_services(llm=ScriptedLLM(RuntimeError("boom")))
    with TestClient(create_app(services)) as client:
        response = client.post("/v1/risk/assess", json={"text": "죽고 싶어요"}, headers=HEADERS)

    assert response.status_code == 200
    assert response.json()["level"] == "high"
    assert response.json()["meta"]["fallback"] is True


def test_utterance_longer_than_chat_limit_is_rejected(client: TestClient) -> None:
    transcript = [{"role": "client", "text": "가" * (MAX_UTTERANCE_CHARS + 1)}]

    response = client.post(
        "/v1/reports/session",
        json={"transcript": transcript, "intake": {"category": "self"}},
        headers=HEADERS,
    )

    assert response.status_code == 422


def test_referral_rejects_repeated_session_number(client: TestClient) -> None:
    entry = {
        "session_no": 1,
        "session_date": "2026-11-01",
        "trainee_label": "수련생 A",
        "digest": {"issues": ["주제"], "key_quotes": [REAL_QUOTE]},
    }

    response = client.post(
        "/v1/reports/referral", json={"digests": [entry, entry]}, headers=HEADERS
    )

    assert response.status_code == 422


# --- 리포트 ---------------------------------------------------------------


async def test_counselor_sentence_is_never_accepted_as_a_client_quote() -> None:
    counselor_line = "많이 지치셨겠어요. 충분히 그럴 수 있어요."
    draft = session_draft(
        distortions=[],
        digest={
            "issues": ["면접 탈락"],
            "key_quotes": [counselor_line, REAL_QUOTE],
            "mood_shift": "차분해졌습니다.",
            "trainee_interventions": ["감정 반영"],
        },
    )
    services = make_services(llm=ScriptedLLM(draft))
    request = SessionReportRequest(
        transcript=[
            Utterance(role="client", text=REAL_QUOTE),
            Utterance(role="provider", text=counselor_line),
        ],
        intake=Intake(category="career"),
    )

    report = await generate_session_report(request, services)

    assert report.digest.key_quotes == [REAL_QUOTE]


async def test_evidence_uses_source_wording_not_the_llm_wording() -> None:
    draft = session_draft(
        distortions=[{"name": "과잉일반화", "evidence": "“뭘 해도   안 될 것 같아요”"}]
    )
    services = make_services(llm=ScriptedLLM(draft))
    request = SessionReportRequest(
        transcript=[Utterance(role="client", text=REAL_QUOTE)], intake=Intake(category="career")
    )

    report = await generate_session_report(request, services)

    assert [d.evidence for d in report.distortions] == ["뭘 해도 안 될 것 같아요"]
    assert report.distortions[0].evidence in REAL_QUOTE


async def test_category_and_intake_tags_are_masked_before_reaching_the_llm() -> None:
    llm = ScriptedLLM(session_draft())
    request = SessionReportRequest(
        transcript=[Utterance(role="client", text=REAL_QUOTE)],
        intake=Intake.model_validate(
            {
                "category": "010-1234-5678",
                "tags": {"emotions": ["불안"], "keywords": ["연락 minsu@example.com"]},
            }
        ),
    )
    tag_llm = ScriptedLLM({"emotions": [], "distortions": [], "keywords": []})

    await generate_session_report(request, make_services(llm=llm))
    await tag_intake(
        TagIntakeRequest(category="010-1234-5678", text="불안해요"), make_services(llm=tag_llm)
    )

    for prompt in (llm.prompts[0], tag_llm.prompts[0]):
        assert "010-1234-5678" not in prompt
    assert "minsu@example.com" not in llm.prompts[0]


def test_red_flag_drops_stale_evidence_when_a_higher_level_arrives() -> None:
    request = ReferralReportRequest(
        digests=[
            DigestEntry.model_validate(
                {
                    "session_no": 1,
                    "session_date": "2026-11-01",
                    "trainee_label": "수련생 A",
                    "digest": Digest(issues=["주제"], key_quotes=[REAL_QUOTE], risk_level="low"),
                }
            )
        ],
        risk_history=[
            RiskHistoryItem(session_no=1, level="medium", evidence="낮은 수준의 근거"),
            RiskHistoryItem(session_no=1, level="high"),
        ],
    )

    red_flag = build_red_flag(request, make_services())

    assert red_flag.max_level == "high"
    assert red_flag.history[0].evidence is None


def test_condense_samples_the_whole_head_and_stays_under_the_limit() -> None:
    lines = [f"내담자: {index:03d}번째 발화 " + "가" * 300 for index in range(200)]

    condensed = condense(lines)

    end_marker = condensed.index("[요약 블록 끝. 아래부터 원문]")
    sampled, tail = condensed[1:end_marker], condensed[end_marker + 1 :]
    head_size = len(lines) - len(tail)
    last_sampled = int(sampled[-1].split("번째")[0].split()[-1])
    assert sum(estimate_tokens(line) for line in condensed) <= MAX_TRANSCRIPT_TOKENS
    assert all(len(line) <= HEAD_UTTERANCE_CHARS for line in sampled)
    assert last_sampled >= head_size - head_size // len(sampled) - 1  # 꼬리 바로 앞까지 고르게
    assert tail == lines[head_size:]


def test_condense_keeps_part_of_an_oversized_last_utterance() -> None:
    lines = ["내담자: 짧은 발화", "내담자: " + "가" * 40_000]

    condensed = condense(lines)

    assert condensed[-1].startswith("내담자: 가가가")
    assert sum(estimate_tokens(line) for line in condensed) <= MAX_TRANSCRIPT_TOKENS


def test_demo_cache_key_rejects_trailing_newline() -> None:
    meta = Meta(provider="fake", model="fake-1", prompt_version="v")

    with pytest.raises(ValueError):
        save_entry("tag_intake", "0" * 64 + "\n", meta)


def test_shared_case_files_are_valid_json_lines() -> None:
    for name in ("risk/rule_cases.jsonl", "masking/pattern_cases.jsonl"):
        lines = (AI_ROOT / name).read_text(encoding="utf-8").splitlines()
        assert all(isinstance(json.loads(line), dict) for line in lines if line.strip())
