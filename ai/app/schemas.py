"""내부 API 계약 모델 (TRD 16-1). 필드는 추가만 하는 하위 호환으로 바꾼다."""

from datetime import date
from typing import Annotated, Literal, Self

from pydantic import BaseModel, Field, model_validator

Level = Literal["none", "low", "medium", "high", "critical"]
LEVELS: tuple[Level, ...] = ("none", "low", "medium", "high", "critical")
TraineeLabel = Literal["수련생 A", "수련생 B"]
EntityType = Literal["연락처", "이메일", "식별번호", "링크", "이름", "지명", "직장", "학교"]

MAX_INTAKE_CHARS = 2000
MAX_RISK_CHARS = 4000
MAX_CONTEXT_ITEMS = 3
MAX_REFERRAL_DIGESTS = 10
MAX_KEY_QUOTES = 6
MAX_UTTERANCE_CHARS = 4000  # 백엔드 채팅 메시지 상한(MessageSendRequest)과 같다
MAX_TAG_CHARS = 50

ShortText = Annotated[str, Field(max_length=MAX_TAG_CHARS)]
LongText = Annotated[str, Field(max_length=MAX_UTTERANCE_CHARS)]


def level_index(level: Level) -> int:
    return LEVELS.index(level)


def max_level(*levels: Level) -> Level:
    return max(levels, key=level_index, default="none")


class Meta(BaseModel):
    provider: str
    model: str
    prompt_version: str
    tokens_in: int = 0
    tokens_out: int = 0
    latency_ms: int = 0
    fallback: bool = False
    cache_hit: bool = False
    safety_filtered: bool = False


# --- AI-01 인테이크 태깅 ---
class TagIntakeRequest(BaseModel):
    category: str = Field(min_length=1, max_length=50)
    text: str = Field(min_length=1, max_length=MAX_INTAKE_CHARS)


class IntakeTags(BaseModel):
    emotions: list[str] = Field(default_factory=list, max_length=5)
    distortions: list[str] = Field(default_factory=list, max_length=3)
    keywords: list[str] = Field(default_factory=list, max_length=8)


class IntakeTagsResponse(IntakeTags):
    meta: Meta


# --- AI-02 매칭 점수 ---
class Candidate(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    tags: list[ShortText] = Field(default_factory=list, max_length=30)


class SpecialtyScoresRequest(BaseModel):
    keywords: list[ShortText] = Field(default_factory=list, max_length=20)
    category: str = Field(min_length=1, max_length=50)
    candidates: list[Candidate]


class SpecialtyScoresResponse(BaseModel):
    scores: dict[str, float]
    meta: Meta


# --- AI-03 Red Flag ---
class RiskRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_RISK_CHARS)
    context: list[LongText] = Field(default_factory=list, max_length=MAX_CONTEXT_ITEMS)
    use_llm: bool = True


class RiskResponse(BaseModel):
    level: Level
    rule_hits: list[str] = Field(default_factory=list)
    llm_score: float | None = None
    evidence: str | None = None
    meta: Meta


# --- AI-04 세션 리포트 ---
class Utterance(BaseModel):
    role: Literal["client", "provider"]
    text: str = Field(min_length=1, max_length=MAX_UTTERANCE_CHARS)


class Intake(BaseModel):
    category: str = Field(min_length=1, max_length=50)
    text: str | None = Field(default=None, max_length=MAX_INTAKE_CHARS)
    tags: IntakeTags | None = None


class SessionReportRequest(BaseModel):
    transcript: list[Utterance] = Field(min_length=1)
    intake: Intake


class DistortionItem(BaseModel):
    name: str
    evidence: str


class RedFlag(BaseModel):
    level: Level
    evidence: str | None = None


class ClientSummary(BaseModel):
    chief_complaint: str
    thinking_patterns: str
    triggers: str
    coping: str
    safety_check: str


class Digest(BaseModel):
    issues: list[str] = Field(default_factory=list)
    key_quotes: list[str] = Field(default_factory=list, max_length=MAX_KEY_QUOTES)
    mood_shift: str = ""
    risk_level: Level = "none"
    trainee_interventions: list[str] = Field(default_factory=list)


class SessionReportResponse(BaseModel):
    chief_complaint: str
    distortions: list[DistortionItem]
    triggers: list[str]
    defenses: list[str]
    red_flag: RedFlag
    client_summary: ClientSummary
    digest: Digest
    meta: Meta


# --- AI-05 연계 종합 리포트 ---
class DigestEntry(BaseModel):
    session_no: int = Field(ge=1)
    session_date: date
    trainee_label: TraineeLabel
    digest: Digest


class RiskHistoryItem(BaseModel):
    session_no: int = Field(ge=1)
    level: Level
    evidence: str | None = Field(default=None, max_length=MAX_UTTERANCE_CHARS)


class ReferralReportRequest(BaseModel):
    digests: list[DigestEntry] = Field(min_length=1, max_length=MAX_REFERRAL_DIGESTS)
    intake_summaries: list[LongText] = Field(default_factory=list)
    risk_history: list[RiskHistoryItem] = Field(default_factory=list)

    @model_validator(mode="after")
    def _unique_sessions(self) -> Self:
        numbers = [entry.session_no for entry in self.digests]
        if len(numbers) != len(set(numbers)):
            raise ValueError("digests must not repeat a session_no")
        return self


class TimelineItem(BaseModel):
    """DB reports.content_enc의 timeline 항목과 같은 형태.

    mood_before·mood_after는 백엔드가 기분 체크 값으로 채운다.
    """

    session_no: int
    session_date: date
    trainee_label: TraineeLabel
    key_points: list[str]


class ReferralDistortion(BaseModel):
    name: str
    evidence: str
    session_no: int


class RiskHistoryPoint(BaseModel):
    session_no: int
    level: Level
    evidence: str | None = None


class ReferralRedFlag(BaseModel):
    max_level: Level
    history: list[RiskHistoryPoint]


class SessionLine(BaseModel):
    session_no: int
    line: str


class ReferralClientSummary(ClientSummary):
    """내담자용 쉬운 말 버전. 연계 리포트는 회기별 한 줄을 포함한다."""

    sessions: list[SessionLine] = Field(default_factory=list)


class ReferralReportResponse(BaseModel):
    chief_complaint: str
    timeline: list[TimelineItem]
    distortions: list[ReferralDistortion]
    triggers: list[str]
    defenses: list[str]
    progress: str
    red_flag: ReferralRedFlag
    focus_suggestions: list[str] = Field(max_length=3)
    client_summary: ReferralClientSummary
    meta: Meta


# --- AI-06 PII 마스킹 ---
class MaskRequest(BaseModel):
    text: str = Field(min_length=1)


class MaskedEntity(BaseModel):
    type: EntityType
    token: str


class MaskResponse(BaseModel):
    text: str
    entities: list[MaskedEntity]
    partial: bool = False
    meta: Meta


# --- AI-07 서류 OCR ---
class OcrResponse(BaseModel):
    name: str | None = None
    cert_name: str | None = None
    cert_number: str | None = None
    issued_on: date | None = None
    issuer: str | None = None
    confidence: float = Field(ge=0.0, le=1.0)
    meta: Meta
