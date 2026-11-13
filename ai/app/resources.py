"""사전·패턴·프롬프트 파일 로더. 기동할 때 한 번 읽고 형식을 검증한다."""

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, Field

from app.schemas import Level

AI_ROOT = Path(__file__).resolve().parent.parent
DICTIONARY_PATH = AI_ROOT / "contract" / "dictionary.json"
RISK_LEXICON_PATH = AI_ROOT / "risk" / "risk_lexicon.yaml"
PII_PATTERNS_PATH = AI_ROOT / "masking" / "pii_patterns.yaml"
PROMPTS_DIR = AI_ROOT / "app" / "prompts"

PROMPT_NAMES = (
    "tag_intake",
    "assess_risk",
    "session_report",
    "referral_report",
    "mask_pii",
    "read_certificate",
)
# 인테이크 카테고리 코드(백엔드 intakes.category) → 프롬프트에 쓸 한글 라벨
CATEGORY_LABELS = {
    "career": "진로·취업",
    "burnout": "직장·번아웃",
    "relationship": "대인관계",
    "romance": "연애",
    "family": "가족",
    "self": "자존감·나 자신",
    "other": "기타",
}
_PROMPT_FILE = re.compile(r"^(?P<name>[a-z_]+)\.v(?P<version>\d+)\.md$")


class TagDictionary(BaseModel):
    version: int
    emotions: list[str] = Field(min_length=1)
    distortions: list[str] = Field(min_length=1)
    specialties: list[str] = Field(min_length=1)

    def category_label(self, code: str) -> str:
        return CATEGORY_LABELS.get(code, code)


class RiskCategory(BaseModel):
    id: str
    label: str
    min_level: Level
    patterns: list[str] = Field(min_length=1)


class Dampeners(BaseModel):
    after_window: int = Field(gt=0)
    before_window: int = Field(gt=0)
    after: list[str]
    before: list[str]


class RiskLexicon(BaseModel):
    version: int
    levels: list[Level]
    categories: list[RiskCategory] = Field(min_length=1)
    dampeners: Dampeners


class PiiPattern(BaseModel):
    id: str
    token: Literal["연락처", "이메일", "식별번호", "링크"]
    normalize: Literal["digits", "lower"]
    pattern: str


class PiiPatterns(BaseModel):
    version: int
    patterns: list[PiiPattern] = Field(min_length=1)


@dataclass(frozen=True)
class Prompt:
    name: str
    version: str  # 예: tag_intake.v1 (응답 meta.prompt_version)
    text: str


@lru_cache
def load_dictionary() -> TagDictionary:
    return TagDictionary.model_validate(json.loads(DICTIONARY_PATH.read_text(encoding="utf-8")))


@lru_cache
def load_risk_lexicon() -> RiskLexicon:
    return RiskLexicon.model_validate(yaml.safe_load(RISK_LEXICON_PATH.read_text(encoding="utf-8")))


@lru_cache
def load_pii_patterns() -> PiiPatterns:
    return PiiPatterns.model_validate(yaml.safe_load(PII_PATTERNS_PATH.read_text(encoding="utf-8")))


@lru_cache
def load_prompts() -> dict[str, Prompt]:
    """이름마다 가장 높은 버전 파일을 고른다. 하나라도 없으면 기동에 실패한다."""
    latest: dict[str, tuple[int, Path]] = {}
    for path in PROMPTS_DIR.glob("*.md"):
        match = _PROMPT_FILE.match(path.name)
        if match is None:
            continue
        name, version = match["name"], int(match["version"])
        if name not in latest or version > latest[name][0]:
            latest[name] = (version, path)
    missing = [name for name in PROMPT_NAMES if name not in latest]
    if missing:
        raise FileNotFoundError(f"prompt files missing: {', '.join(missing)}")
    return {
        name: Prompt(name=name, version=f"{name}.v{version}", text=path.read_text(encoding="utf-8"))
        for name, (version, path) in latest.items()
    }
