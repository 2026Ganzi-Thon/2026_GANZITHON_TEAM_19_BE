"""리포트 후처리: 근거 인용 검증, 위험 수준, 출력 안전 필터, 긴 대화 줄이기."""

from datetime import date
from typing import Any

from app.functions.referral_report import generate_referral_report
from app.functions.session_report import (
    MAX_TRANSCRIPT_TOKENS,
    condense,
    estimate_tokens,
    generate_session_report,
)
from app.safety.evidence import locate_quote
from app.safety.output_filter import filter_text
from app.schemas import (
    Digest,
    DigestEntry,
    Intake,
    ReferralReportRequest,
    RiskHistoryItem,
    SessionReportRequest,
    Utterance,
)
from tests.conftest import ScriptedLLM, make_services

REAL_QUOTE = "저는 뭘 해도 안 될 것 같아요."
SUMMARY = {
    "chief_complaint": "요즘 힘든 일을 살펴봤어요.",
    "thinking_patterns": "나쁜 쪽으로 생각이 흘러가요.",
    "triggers": "면접 결과를 들을 때 힘들어요.",
    "coping": "혼자 버티려고 해요.",
}


def session_draft(**overrides: Any) -> dict[str, Any]:
    draft: dict[str, Any] = {
        "chief_complaint": "면접 탈락이 이어져 무력감을 호소했습니다.",
        "distortions": [
            {"name": "과잉일반화", "evidence": REAL_QUOTE},
            {"name": "파국화", "evidence": "인생이 완전히 끝났어요."},
            {"name": "없는 왜곡", "evidence": REAL_QUOTE},
        ],
        "triggers": ["면접 결과 통보"],
        "defenses": ["회피"],
        "risk_level": "none",
        "client_summary": SUMMARY,
        "digest": {
            "issues": ["면접 탈락"],
            "key_quotes": [REAL_QUOTE, "지어낸 문장입니다."],
            "mood_shift": "조금 가라앉았습니다.",
            "trainee_interventions": ["감정 반영"],
        },
    }
    return {**draft, **overrides}


def session_request(*client_lines: str) -> SessionReportRequest:
    lines = client_lines or (f"면접에서 또 떨어졌어요. {REAL_QUOTE}",)
    return SessionReportRequest(
        transcript=[Utterance(role="client", text=line) for line in lines],
        intake=Intake(category="career"),
    )


async def test_session_report_drops_quotes_not_found_in_transcript() -> None:
    services = make_services(llm=ScriptedLLM(session_draft()))

    report = await generate_session_report(session_request(), services)

    assert [(d.name, d.evidence) for d in report.distortions] == [("과잉일반화", REAL_QUOTE)]
    assert report.digest.key_quotes == [REAL_QUOTE]


async def test_session_report_risk_cannot_go_below_rule_level() -> None:
    services = make_services(llm=ScriptedLLM(session_draft(risk_level="none")))

    report = await generate_session_report(session_request("그냥 죽고 싶어요."), services)

    assert report.red_flag.level == "high"
    assert report.digest.risk_level == "high"
    assert report.red_flag.evidence == "그냥 죽고 싶어요."
    assert "109" in report.client_summary.safety_check


async def test_session_report_llm_can_raise_risk_above_rule_level() -> None:
    services = make_services(llm=ScriptedLLM(session_draft(risk_level="medium")))

    report = await generate_session_report(session_request(), services)

    assert report.red_flag.level == "medium"


async def test_session_report_removes_diagnosis_and_medication_sentences() -> None:
    draft = session_draft(
        chief_complaint="면접 탈락으로 힘들어합니다. 우울증으로 보입니다.",
        triggers=["면접 결과 통보", "항우울제를 복용하면 나아질 것입니다."],
    )
    services = make_services(llm=ScriptedLLM(draft))

    report = await generate_session_report(session_request(), services)

    assert report.chief_complaint == "면접 탈락으로 힘들어합니다."
    assert report.triggers == ["면접 결과 통보"]
    assert report.meta.safety_filtered is True


async def test_session_report_sends_masked_transcript_to_llm() -> None:
    llm = ScriptedLLM(session_draft())
    services = make_services(llm=llm)

    await generate_session_report(session_request("제 번호는 010-1234-5678이에요."), services)

    assert "010-1234-5678" not in llm.prompts[0]
    assert "내담자: 제 번호는 [연락처1]이에요." in llm.prompts[0]


def referral_request() -> ReferralReportRequest:
    def entry(session_no: int, quote: str, level: str = "none") -> DigestEntry:
        return DigestEntry(
            session_no=session_no,
            session_date=date(2026, 11, session_no),
            trainee_label="수련생 A",
            digest=Digest(issues=[f"{session_no}회기 주제"], key_quotes=[quote], risk_level=level),  # type: ignore[arg-type]
        )

    return ReferralReportRequest(
        digests=[
            entry(1, "첫 회기 발화예요."),
            entry(2, REAL_QUOTE, "medium"),
            entry(3, "셋째 발화예요."),
        ],
        risk_history=[
            RiskHistoryItem(
                session_no=1, level="high", evidence="연락처 010-1234-5678 죽고 싶어요"
            ),
            RiskHistoryItem(session_no=1, level="low"),
        ],
    )


def referral_draft(**overrides: Any) -> dict[str, Any]:
    draft: dict[str, Any] = {
        "chief_complaint": "취업 스트레스가 이어졌습니다.",
        "timeline": [
            {"session_no": 1, "key_points": ["첫 만남"], "client_line": "처음 이야기를 나눴어요."},
            {"session_no": 9, "key_points": ["없는 회기"], "client_line": "없는 회기예요."},
        ],
        "distortions": [
            {"name": "과잉일반화", "evidence": REAL_QUOTE, "session_no": 1},
            {"name": "파국화", "evidence": "digest에 없는 문장", "session_no": 2},
        ],
        "triggers": ["면접"],
        "defenses": ["회피"],
        "progress": "말로 옮기는 일이 늘었습니다.",
        "focus_suggestions": ["하나", "둘", "셋", "넷"],
        "client_summary": SUMMARY,
    }
    return {**draft, **overrides}


async def test_referral_keeps_only_quotes_from_digests_and_fixes_session_no() -> None:
    services = make_services(llm=ScriptedLLM(referral_draft()))

    report = await generate_referral_report(referral_request(), services)

    assert [(d.name, d.session_no) for d in report.distortions] == [("과잉일반화", 2)]


async def test_referral_timeline_has_every_input_session_and_no_invented_one() -> None:
    services = make_services(llm=ScriptedLLM(referral_draft()))

    report = await generate_referral_report(referral_request(), services)

    assert [t.session_no for t in report.timeline] == [1, 2, 3]
    assert report.timeline[0].key_points == ["첫 만남"]
    assert report.timeline[1].key_points == ["2회기 주제"]  # LLM이 빠뜨린 회기는 digest로 채움
    assert [line.session_no for line in report.client_summary.sessions] == [1]


async def test_referral_red_flag_is_filled_by_code_from_history() -> None:
    services = make_services(llm=ScriptedLLM(referral_draft()))

    report = await generate_referral_report(referral_request(), services)

    assert report.red_flag.max_level == "high"
    assert [(p.session_no, p.level) for p in report.red_flag.history] == [
        (1, "high"),
        (2, "medium"),
        (3, "none"),
    ]
    assert report.red_flag.history[0].evidence == "연락처 [연락처1] 죽고 싶어요"
    assert len(report.focus_suggestions) == 3


async def test_referral_does_not_send_risk_evidence_or_raw_pii_to_llm() -> None:
    llm = ScriptedLLM(referral_draft())
    services = make_services(llm=llm)

    await generate_referral_report(referral_request(), services)

    assert "010-1234-5678" not in llm.prompts[0]
    assert "죽고 싶어요" not in llm.prompts[0]


def test_condense_keeps_tail_and_shrinks_head_under_limit() -> None:
    lines = [f"내담자: {index}번째 발화 " + "가" * 300 for index in range(200)]

    condensed = condense(lines)

    assert condensed[0].startswith("[앞부분 요약 블록")
    assert condensed[-1] == lines[-1]
    assert sum(estimate_tokens(line) for line in condensed) <= MAX_TRANSCRIPT_TOKENS


def test_condense_leaves_short_transcript_untouched() -> None:
    lines = ["내담자: 안녕하세요", "상담자: 반가워요"]

    assert condense(lines) == lines


def test_filter_text_keeps_safe_sentences() -> None:
    result = filter_text("잠을 잘 못 잔다고 했습니다. 불안장애입니다. 식사는 거르지 않습니다.")

    assert result.text == "잠을 잘 못 잔다고 했습니다. 식사는 거르지 않습니다."
    assert result.filtered is True


def test_quote_is_returned_in_the_source_wording() -> None:
    source = "저는 뭘 해도 안 될 것 같아요."

    assert locate_quote("“뭘 해도   안 될 것 같아요”", [source]) == (0, "뭘 해도 안 될 것 같아요")
    assert locate_quote("전혀 다른 문장", [source]) is None
    assert locate_quote("요", [source]) is None  # 너무 짧은 조각은 근거로 쓰지 않는다
