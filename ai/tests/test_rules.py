"""규칙 엔진과 정규식 마스킹의 공통 사례. 백엔드 Java 엔진도 같은 파일을 통과해야 한다."""

import json
from pathlib import Path
from typing import Any

import pytest

from app.functions.drafts import EntityDraft
from app.functions.masking import Numbering, PiiMasker, apply_entities
from app.functions.risk import RiskRuleEngine, step_down
from app.resources import AI_ROOT, load_pii_patterns, load_risk_lexicon


def load_cases(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]


RULE_CASES = load_cases(AI_ROOT / "risk" / "rule_cases.jsonl")
PATTERN_CASES = load_cases(AI_ROOT / "masking" / "pattern_cases.jsonl")


@pytest.fixture(scope="module")
def engine() -> RiskRuleEngine:
    return RiskRuleEngine(load_risk_lexicon())


@pytest.fixture(scope="module")
def masker() -> PiiMasker:
    return PiiMasker(load_pii_patterns())


@pytest.mark.parametrize("case", RULE_CASES, ids=[f"rule{i}" for i in range(len(RULE_CASES))])
def test_rule_engine_matches_shared_case(engine: RiskRuleEngine, case: dict[str, Any]) -> None:
    verdict = engine.check(case["text"])

    assert verdict.level == case["level"]
    assert list(verdict.hits) == case["hits"]


@pytest.mark.parametrize(
    "case", PATTERN_CASES, ids=[f"pattern{i}" for i in range(len(PATTERN_CASES))]
)
def test_masker_matches_shared_case(masker: PiiMasker, case: dict[str, Any]) -> None:
    assert masker.mask(case["text"]) == case["masked"]


def test_negation_lowers_one_step_but_never_removes_the_hit(engine: RiskRuleEngine) -> None:
    verdict = engine.check("죽고 싶을 정도는 아니에요")

    assert verdict.level == "medium"
    assert verdict.hits == ("suicidal_ideation",)


def test_step_down_stops_at_low() -> None:
    assert step_down("critical") == "high"
    assert step_down("low") == "low"


def test_evidence_is_the_sentence_containing_the_match(engine: RiskRuleEngine) -> None:
    verdict = engine.check("오늘 날씨가 좋네요. 그런데 죽고 싶어요. 밥은 먹었어요.")

    assert verdict.evidence == "그런데 죽고 싶어요."


def test_same_entity_gets_same_number_across_utterances(masker: PiiMasker) -> None:
    masked = masker.mask_many(
        ["전화는 010-1234-5678", "아까 그 010 1234 5678 맞죠", "010-0000-1111"]
    )

    assert masked == ["전화는 [연락처1]", "아까 그 [연락처1] 맞죠", "[연락처2]"]


def test_apply_entities_replaces_every_occurrence_and_keeps_tokens() -> None:
    numbering = Numbering()
    entities = [
        EntityDraft(type="이름", text="민수"),
        EntityDraft(type="직장", text="한빛상사"),
        EntityDraft(type="이름", text="[이름1]"),
    ]

    masked = apply_entities(
        "민수가 한빛상사에서 민수 선배를 만났어요 [연락처1]", entities, numbering
    )

    assert masked == "[이름1]가 [직장1]에서 [이름1] 선배를 만났어요 [연락처1]"
    assert {e.token for e in numbering.entities()} == {"[이름1]", "[직장1]"}
