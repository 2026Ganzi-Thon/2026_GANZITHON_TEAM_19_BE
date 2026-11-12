"""AI-05 연계 종합 리포트 (TRD 16-2-1 ②).

대화 원문을 다시 읽지 않고 회기별 digest만 모아 만든다. 근거 인용은 digest의
key_quotes에서만 고르고, 위험 수준과 이력은 LLM이 아니라 코드가 입력 이력으로 채운다.
"""

import json
import time
from typing import TYPE_CHECKING

from app.functions.drafts import ReferralDraft
from app.functions.masking import Numbering
from app.functions.report_common import SAFETY_CHECK, SafetyTracker, client_summary_fields
from app.meta import build_meta
from app.safety.evidence import canonical_quote
from app.schemas import (
    Digest,
    DigestEntry,
    Level,
    ReferralClientSummary,
    ReferralDistortion,
    ReferralRedFlag,
    ReferralReportRequest,
    ReferralReportResponse,
    RiskHistoryPoint,
    SessionLine,
    TimelineItem,
    level_index,
    max_level,
)

if TYPE_CHECKING:
    from app.services import Services

REFERRAL_TIMEOUT_SECONDS = 90.0
REFERRAL_MAX_TOKENS = 2500
MAX_FOCUS_SUGGESTIONS = 3


def _masked_entries(
    req: ReferralReportRequest, services: "Services", numbering: Numbering
) -> list[DigestEntry]:
    """digest 문자열마다 정규식 마스킹을 한 번 더 건 사본."""

    def mask(text: str) -> str:
        return services.masker.mask(text, numbering)

    return [
        entry.model_copy(
            update={
                "digest": Digest(
                    issues=[mask(v) for v in entry.digest.issues],
                    key_quotes=[mask(v) for v in entry.digest.key_quotes],
                    mood_shift=mask(entry.digest.mood_shift),
                    risk_level=entry.digest.risk_level,
                    trainee_interventions=[mask(v) for v in entry.digest.trainee_interventions],
                )
            }
        )
        for entry in req.digests
    ]


def _payload(
    entries: list[DigestEntry],
    req: ReferralReportRequest,
    services: "Services",
    numbering: Numbering,
) -> dict[str, object]:
    """LLM에 보낼 입력. 위험 이력의 근거 문장은 보내지 않는다."""
    return {
        "digests": [entry.model_dump(mode="json") for entry in entries],
        "intake_summaries": [services.masker.mask(v, numbering) for v in req.intake_summaries],
        "risk_history": [
            {"session_no": item.session_no, "level": item.level} for item in req.risk_history
        ],
    }


def build_red_flag(req: ReferralReportRequest, services: "Services") -> ReferralRedFlag:
    """회기별 최댓값 = max(위험 이력, 세션 리포트 level). LLM 출력은 쓰지 않는다."""
    levels: dict[int, Level] = {e.session_no: e.digest.risk_level for e in req.digests}
    evidence: dict[int, str] = {}
    for item in req.risk_history:
        current = levels.get(item.session_no, "none")
        if level_index(item.level) < level_index(current):
            continue
        if level_index(item.level) > level_index(current):
            # 더 높은 수준이 나오면 낮은 수준의 근거는 버린다.
            levels[item.session_no] = item.level
            evidence.pop(item.session_no, None)
        if item.evidence and item.session_no not in evidence:
            evidence[item.session_no] = services.masker.mask(item.evidence)
    history = [
        RiskHistoryPoint(session_no=no, level=level, evidence=evidence.get(no))
        for no, level in sorted(levels.items())
    ]
    return ReferralRedFlag(max_level=max_level(*levels.values()), history=history)


def _timeline(
    draft: ReferralDraft, entries: dict[int, DigestEntry], tracker: SafetyTracker
) -> tuple[list[TimelineItem], list[SessionLine]]:
    drafted = {item.session_no: item for item in draft.timeline if item.session_no in entries}
    timeline: list[TimelineItem] = []
    lines: list[SessionLine] = []
    for session_no, entry in sorted(entries.items()):
        item = drafted.get(session_no)
        # LLM이 회기를 빠뜨리면 digest의 주요 호소로 채워 누락을 막는다.
        points = tracker.items(item.key_points) if item else []
        timeline.append(
            TimelineItem(
                session_no=session_no,
                session_date=entry.session_date,
                trainee_label=entry.trainee_label,
                key_points=points or tracker.items(entry.digest.issues),
            )
        )
        line = tracker.text(item.client_line) if item else ""
        if line:
            lines.append(SessionLine(session_no=session_no, line=line))
    return timeline, lines


def _distortions(
    draft: ReferralDraft, entries: dict[int, DigestEntry], allowed_names: list[str]
) -> list[ReferralDistortion]:
    """evidence가 digest key_quotes 집합에 없으면 그 항목을 지운다 (TRD 16-2-1).

    key_quotes 가운데 하나와 통째로 같아야 한다 (따옴표·띄어쓰기 차이만 허용).
    """
    ordered = sorted(entries.items())
    quotes = [(no, quote) for no, entry in ordered for quote in entry.digest.key_quotes]
    by_canonical = {canonical_quote(quote): (no, quote) for no, quote in reversed(quotes)}
    kept: list[ReferralDistortion] = []
    for item in draft.distortions:
        found = by_canonical.get(canonical_quote(item.evidence))
        if found is None or item.name not in allowed_names:
            continue
        session_no, quote = found
        # 글자는 digest 쪽 것을 쓰고, 회기 번호는 인용이 실제로 나온 회기로 맞춘다.
        kept.append(ReferralDistortion(name=item.name, evidence=quote, session_no=session_no))
    return kept


async def generate_referral_report(
    req: ReferralReportRequest, services: "Services"
) -> ReferralReportResponse:
    started = time.perf_counter()
    prompt = services.prompts["referral_report"]
    numbering = Numbering()
    entries = _masked_entries(req, services, numbering)
    payload = _payload(entries, req, services, numbering)
    user = f"<user_text>\n{json.dumps(payload, ensure_ascii=False)}\n</user_text>"
    result = await services.gateway.complete(
        system=prompt.text.replace("{distortions}", ", ".join(services.dictionary.distortions)),
        user=user,
        schema=ReferralDraft,
        timeout=REFERRAL_TIMEOUT_SECONDS,
        max_tokens=REFERRAL_MAX_TOKENS,
    )
    draft = result.parsed
    tracker = SafetyTracker()
    # 인용 검증은 LLM이 본 것과 같은 마스킹된 digest로 한다.
    masked_entries = {entry.session_no: entry for entry in entries}
    red_flag = build_red_flag(req, services)
    timeline, lines = _timeline(draft, masked_entries, tracker)

    return ReferralReportResponse(
        chief_complaint=tracker.text(draft.chief_complaint, required=True),
        timeline=timeline,
        distortions=_distortions(draft, masked_entries, services.dictionary.distortions),
        triggers=tracker.items(draft.triggers),
        defenses=tracker.items(draft.defenses),
        progress=tracker.text(draft.progress),
        red_flag=red_flag,
        focus_suggestions=tracker.items(draft.focus_suggestions)[:MAX_FOCUS_SUGGESTIONS],
        client_summary=ReferralClientSummary(
            **client_summary_fields(draft.client_summary, tracker),
            safety_check=SAFETY_CHECK[red_flag.max_level],
            sessions=lines,
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
