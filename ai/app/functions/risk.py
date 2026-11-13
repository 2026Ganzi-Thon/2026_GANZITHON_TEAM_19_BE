"""Red Flag 2단계 판정 (TRD 16-3). 규칙 필터(항상, 네트워크 없음) + LLM 분류.

최종 level = max(규칙, LLM). LLM이 규칙보다 낮게 봐도 규칙 결과를 낮추지 않는다.
"""

import re
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING

from app import errors
from app.functions.drafts import RiskDraft
from app.functions.masking import Numbering, PiiMasker, to_masked_offset
from app.logging import get_logger
from app.meta import build_meta
from app.resources import RiskLexicon
from app.schemas import LEVELS, Level, RiskRequest, RiskResponse, level_index, max_level
from app.text import normalize

if TYPE_CHECKING:
    from app.services import Services

log = get_logger()

RISK_LLM_TIMEOUT_SECONDS = 3.0
RISK_MAX_TOKENS = 100
EVIDENCE_MAX_CHARS = 120
_SENTENCE_END = re.compile(r"[.!?…\n]")


@dataclass(frozen=True)
class RuleVerdict:
    level: Level
    hits: tuple[str, ...]
    evidence: str | None  # 원문 그대로의 근거 문장. 밖으로 내보낼 때는 masked_evidence를 쓴다
    span: tuple[int, int] | None = None  # 가장 높은 수준을 만든 일치의 위치 (정규화한 글 기준)


def step_down(level: Level) -> Level:
    """한 단계 낮춘다. low 아래로는 내려가지 않는다 (없어지지 않는다)."""
    return LEVELS[max(1, level_index(level) - 1)]


def sentence_around(text: str, start: int, end: int) -> str:
    left = max((m.end() for m in _SENTENCE_END.finditer(text, 0, start)), default=0)
    right_match = _SENTENCE_END.search(text, end)
    right = right_match.end() if right_match else len(text)
    return text[left:right].strip()[:EVIDENCE_MAX_CHARS]


class RiskRuleEngine:
    """백엔드 RiskRuleEngine(Java)과 같은 사전·같은 규칙. risk/rule_cases.jsonl로 맞춘다."""

    def __init__(self, lexicon: RiskLexicon) -> None:
        self._categories = [
            (c.id, c.min_level, [re.compile(p, re.ASCII) for p in c.patterns])
            for c in lexicon.categories
        ]
        self._after = [re.compile(p, re.ASCII) for p in lexicon.dampeners.after]
        self._before = [re.compile(p, re.ASCII) for p in lexicon.dampeners.before]
        self._after_window = lexicon.dampeners.after_window
        self._before_window = lexicon.dampeners.before_window

    def check(self, text: str) -> RuleVerdict:
        normalized = normalize(text)
        level: Level = "none"
        span: tuple[int, int] | None = None
        hits: list[str] = []
        for category_id, min_level, patterns in self._categories:
            for pattern in patterns:
                for match in pattern.finditer(normalized):
                    if category_id not in hits:
                        hits.append(category_id)
                    dampened = self._dampened(normalized, match.start(), match.end())
                    candidate = step_down(min_level) if dampened else min_level
                    if level_index(candidate) > level_index(level):
                        level = candidate
                        span = match.span()
        evidence = sentence_around(normalized, *span) if span else None
        return RuleVerdict(level=level, hits=tuple(hits), evidence=evidence, span=span)

    def _dampened(self, text: str, start: int, end: int) -> bool:
        after = text[end : end + self._after_window]
        cut = _SENTENCE_END.search(after)
        if cut:
            after = after[: cut.start()]
        before = text[max(0, start - self._before_window) : start]
        boundaries = list(_SENTENCE_END.finditer(before))
        if boundaries:
            before = before[boundaries[-1].end() :]
        return any(p.search(after) for p in self._after) or any(
            p.search(before) for p in self._before
        )


def masked_evidence(
    text: str, rule: RuleVerdict, masker: PiiMasker, numbering: Numbering | None = None
) -> str | None:
    """연락처·이메일·링크를 가린 근거 문장.

    규칙 판정은 백엔드 엔진과 결과가 같도록 원문으로 한다. 내보낼 문장은 글 전체를
    먼저 가린 뒤 그 안에서 고른다. 문장을 먼저 자르고 가리면, 마침표가 든 링크나
    이메일이 조각나서 가려지지 않는다.
    """
    if rule.span is None:
        return None
    masked, replaced = masker.mask_with_offsets(text, numbering)
    start = to_masked_offset(rule.span[0], replaced, is_end=False)
    end = to_masked_offset(rule.span[1], replaced, is_end=True)
    return sentence_around(masked, start, end)


def _from_rule(
    rule: RuleVerdict,
    req: RiskRequest,
    services: "Services",
    version: str,
    started: float,
    *,
    fallback: bool,
) -> RiskResponse:
    return RiskResponse(
        level=rule.level,
        rule_hits=list(rule.hits),
        llm_score=None,
        evidence=masked_evidence(req.text, rule, services.masker),
        meta=build_meta(services, version, started, fallback=fallback),
    )


async def assess_risk(req: RiskRequest, services: "Services") -> RiskResponse:
    started = time.perf_counter()
    prompt = services.prompts["assess_risk"]
    rule = services.risk_engine.check(req.text)  # 동기, 수 ms
    if not req.use_llm or await services.budget.exhausted():
        # 위험 판정은 상한에 걸려도 429 대신 규칙 결과를 200으로 돌려준다 (16-7, 16-9).
        return _from_rule(rule, req, services, prompt.version, started, fallback=True)

    masked_text, *masked_context = services.masker.mask_many([req.text, *req.context], Numbering())
    context_block = "\n".join(f"- {line}" for line in masked_context) or "(없음)"
    user = f"<context>\n{context_block}\n</context>\n<user_text>\n{masked_text}\n</user_text>"
    try:
        result = await services.gateway.complete(
            system=prompt.text,
            user=user,
            schema=RiskDraft,
            timeout=RISK_LLM_TIMEOUT_SECONDS,
            max_tokens=RISK_MAX_TOKENS,
            lane="risk",
        )
    except errors.AIError:
        return _from_rule(rule, req, services, prompt.version, started, fallback=True)
    except Exception as exc:
        # 위험 판정은 어떤 오류에도 규칙 결과로 답한다. 메시지는 남기지 않고 종류만 남긴다.
        log.error("risk_llm_unexpected_error", error_type=type(exc).__name__)
        return _from_rule(rule, req, services, prompt.version, started, fallback=True)

    draft = result.parsed
    level = max_level(rule.level, draft.level)
    span = draft.evidence_span.strip()
    llm_evidence = span[:EVIDENCE_MAX_CHARS] if span and span in masked_text else None
    llm_is_higher = level_index(draft.level) > level_index(rule.level)
    rule_evidence = masked_evidence(req.text, rule, services.masker)
    evidence = (llm_evidence if llm_is_higher else None) or rule_evidence or llm_evidence
    return RiskResponse(
        level=level,
        rule_hits=list(rule.hits),
        llm_score=round(min(max(draft.score, 0.0), 1.0), 3),
        evidence=evidence if level != "none" else None,
        meta=build_meta(
            services,
            prompt.version,
            started,
            model=result.model,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
        ),
    )
