"""AI-04 세션 리포트 + digest (TRD 16-2-1 ①).

대화 원문은 회기당 한 번만 LLM에 보낸다. 연계 리포트는 여기서 만든 digest만 쓴다.
"""

import time
from collections.abc import Callable
from typing import TYPE_CHECKING

from app.functions.drafts import SessionDraft
from app.functions.masking import Numbering
from app.functions.report_common import SafetyTracker, build_client_summary
from app.functions.risk import masked_evidence
from app.meta import build_meta
from app.safety.evidence import QuoteIndex
from app.schemas import (
    MAX_KEY_QUOTES,
    Digest,
    DistortionItem,
    Level,
    RedFlag,
    SessionReportRequest,
    SessionReportResponse,
    max_level,
)

if TYPE_CHECKING:
    from app.services import Services

SESSION_TIMEOUT_SECONDS = 60.0
SESSION_MAX_TOKENS = 2000
MAX_TRANSCRIPT_TOKENS = 16_000
HEAD_SUMMARY_TOKENS = 2_000
MARKER_TOKENS = 100  # 요약 블록의 머리말·꼬리말 몫
CHARS_PER_TOKEN = 1.5  # 한국어 기준 대략값
HEAD_UTTERANCE_CHARS = 80
MAX_DISTORTION_ITEMS = 10  # LLM이 돌려준 목록에서 검증해 볼 개수
MAX_QUOTE_CANDIDATES = 12
ROLE_LABELS = {"client": "내담자", "provider": "상담자"}


def estimate_tokens(text: str) -> int:
    return int(len(text) / CHARS_PER_TOKEN) + 1


def _evenly_spaced(items: list[str], count: int) -> list[str]:
    """처음부터 끝까지 고르게 count개를 뽑는다."""
    if len(items) <= count:
        return items
    return [items[index * len(items) // count] for index in range(count)]


def condense(lines: list[str]) -> list[str]:
    """16,000토큰을 넘으면 앞부분을 요약 블록으로 줄인다 (TRD 16-11).

    뒷부분은 그대로 두고, 앞부분은 발화를 고르게 뽑아 앞 80자만 남긴다.
    """
    if sum(estimate_tokens(line) for line in lines) <= MAX_TRANSCRIPT_TOKENS:
        return lines
    tail: list[str] = []
    budget = MAX_TRANSCRIPT_TOKENS - HEAD_SUMMARY_TOKENS - MARKER_TOKENS
    for line in reversed(lines):
        cost = estimate_tokens(line)
        if cost > budget:
            if not tail:
                # 마지막 발화 하나가 예산보다 길면 잘라서라도 원문을 남긴다.
                tail.append(line[: int((budget - 1) * CHARS_PER_TOKEN)])
            break
        tail.append(line)
        budget -= cost
    tail.reverse()
    head = lines[: len(lines) - len(tail)]
    keep = HEAD_SUMMARY_TOKENS // estimate_tokens("가" * HEAD_UTTERANCE_CHARS)
    sampled = _evenly_spaced([line[:HEAD_UTTERANCE_CHARS] for line in head], keep)
    header = f"[앞부분 요약 블록: 앞선 발화 {len(head)}개 가운데 {len(sampled)}개를 줄여서 표시]"
    return [header, *sampled, "[요약 블록 끝. 아래부터 원문]", *tail]


def _intake_block(
    req: SessionReportRequest,
    services: "Services",
    masked_intake: str,
    mask: Callable[[str], str],
) -> str:
    # 태그와 카테고리도 백엔드가 넘긴 글이므로 LLM에 보내기 전에 마스킹한다.
    intake = req.intake
    lines = [f"인테이크 카테고리: {mask(services.dictionary.category_label(intake.category))}"]
    if intake.tags is not None:
        emotions = ", ".join(mask(tag) for tag in intake.tags.emotions) or "없음"
        keywords = ", ".join(mask(tag) for tag in intake.tags.keywords) or "없음"
        lines.append(f"인테이크 감정 태그: {emotions}")
        lines.append(f"인테이크 키워드: {keywords}")
    if masked_intake:
        lines.append(f"<intake_text>\n{masked_intake}\n</intake_text>")
    return "\n".join(lines)


async def generate_session_report(
    req: SessionReportRequest, services: "Services"
) -> SessionReportResponse:
    started = time.perf_counter()
    prompt = services.prompts["session_report"]
    numbering = Numbering()
    masked = services.masker.mask_many([u.text for u in req.transcript], numbering)
    masked_intake = services.masker.mask(req.intake.text or "", numbering)
    client_texts = [
        text
        for utterance, text in zip(req.transcript, masked, strict=True)
        if utterance.role == "client"
    ]
    lines = [
        f"{ROLE_LABELS[utterance.role]}: {text}"
        for utterance, text in zip(req.transcript, masked, strict=True)
    ]
    intake_block = _intake_block(
        req, services, masked_intake, lambda text: services.masker.mask(text, numbering)
    )
    user = f"{intake_block}\n<user_text>\n{chr(10).join(condense(lines))}\n</user_text>"
    result = await services.gateway.complete(
        system=prompt.text.replace("{distortions}", ", ".join(services.dictionary.distortions)),
        user=user,
        schema=SessionDraft,
        timeout=SESSION_TIMEOUT_SECONDS,
        max_tokens=SESSION_MAX_TOKENS,
    )
    draft = result.parsed
    tracker = SafetyTracker()

    # 근거 인용은 내담자 발화에 실제로 있는 문장만 남기고, 글자는 원문 쪽 것을 쓴다.
    # 상담자 발화는 내담자 인용이 될 수 없다.
    quotes = QuoteIndex(client_texts)
    located = [
        (d.name, quotes.locate(d.evidence))
        for d in draft.distortions[:MAX_DISTORTION_ITEMS]
        if d.name in services.dictionary.distortions
    ]
    distortions = [DistortionItem(name=name, evidence=found[1]) for name, found in located if found]
    key_quotes = quotes.verbatim(draft.digest.key_quotes[:MAX_QUOTE_CANDIDATES], MAX_KEY_QUOTES)

    # 위험 수준은 규칙 엔진 결과보다 낮아질 수 없다. 규칙 판정은 백엔드 엔진과 결과가
    # 같도록 원문으로 하고, 내보내는 근거 문장만 대화와 같은 번호로 가린다.
    verdicts = [
        (utterance.text, services.risk_engine.check(utterance.text))
        for utterance in req.transcript
        if utterance.role == "client"
    ]
    rule_level: Level = max_level(*(verdict.level for _, verdict in verdicts))
    level = max_level(rule_level, draft.risk_level)
    rule_evidence = next(
        (
            masked_evidence(text, verdict, services.masker, numbering)
            for text, verdict in verdicts
            if verdict.level == rule_level
        ),
        None,
    )

    return SessionReportResponse(
        chief_complaint=tracker.text(draft.chief_complaint, required=True),
        distortions=distortions,
        triggers=tracker.items(draft.triggers),
        defenses=tracker.items(draft.defenses),
        red_flag=RedFlag(level=level, evidence=rule_evidence if level != "none" else None),
        client_summary=build_client_summary(draft.client_summary, level, tracker),
        digest=Digest(
            issues=tracker.items(draft.digest.issues),
            key_quotes=key_quotes,
            mood_shift=tracker.text(draft.digest.mood_shift),
            risk_level=level,
            trainee_interventions=tracker.items(draft.digest.trainee_interventions),
        ),
        meta=build_meta(
            services,
            prompt.version,
            started,
            model=result.model,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
            safety_filtered=tracker.filtered,
        ),
    )
