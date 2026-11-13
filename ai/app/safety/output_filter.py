"""출력 안전 필터 (TRD 16-4, 16-10).

진단명 단정, 약물명·처방 표현이 들어간 문장을 지운다. 내담자 발화를 그대로 옮긴
인용(evidence, key_quotes)에는 적용하지 않는다.
"""

import re
from dataclasses import dataclass

_DISORDERS = (
    r"(?:우울증|조울증|조현병|공황\s*장애|불안\s*장애|강박\s*장애|양극성\s*장애|"
    r"적응\s*장애|섭식\s*장애|수면\s*장애|성격\s*장애|ADHD|PTSD|"
    r"외상\s*후\s*스트레스\s*장애|[가-힣]{1,10}\s{0,3}장애)"
)
_ASSERTIONS = (
    r"(?:입니다|이에요|예요|임\b|(?:으)?로\s*(?:진단|보입니다|보여요|판단|추정)|"
    r"(?:이|가)\s*(?:있습니다|있어요|의심|확실)|(?:을|를)\s*앓)"
)
DIAGNOSIS_PATTERN = re.compile(_DISORDERS + r"\s*" + _ASSERTIONS)
MEDICATION_PATTERN = re.compile(
    r"항우울제|항불안제|수면제|신경안정제|안정제|SSRI|프로작|졸피뎀|자낙스|렉사프로|"
    r"약물\s*치료|약을\s*(?:복용|드시|드세|먹어|처방)|처방|복용량|투약"
)
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?…])\s+|\n+")


@dataclass(frozen=True)
class Filtered:
    text: str
    filtered: bool


def is_unsafe(sentence: str) -> bool:
    return bool(DIAGNOSIS_PATTERN.search(sentence) or MEDICATION_PATTERN.search(sentence))


def filter_text(text: str) -> Filtered:
    sentences = [s for s in _SENTENCE_SPLIT.split(text) if s.strip()]
    kept = [s for s in sentences if not is_unsafe(s)]
    if len(kept) == len(sentences):
        return Filtered(text=text, filtered=False)
    return Filtered(text=" ".join(kept).strip(), filtered=True)


def filter_items(items: list[str]) -> tuple[list[str], bool]:
    """항목마다 걸러 내고, 통째로 지워진 항목은 뺀다."""
    results = [filter_text(item) for item in items]
    kept = [r.text for r in results if r.text]
    return kept, any(r.filtered for r in results)
