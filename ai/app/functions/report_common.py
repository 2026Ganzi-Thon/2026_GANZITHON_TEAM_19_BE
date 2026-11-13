"""세션·연계 리포트가 함께 쓰는 후처리: 안전 필터, 내담자용 요약, 고정 안전 문구."""

from app.functions.drafts import SummaryDraft
from app.safety.output_filter import filter_text
from app.schemas import ClientSummary, Level

# 내담자용 안전 점검은 level에 따른 고정 문구만 쓴다 (TRD 16-4). LLM이 쓰지 않는다.
_SAFE = "오늘 대화에서 안전과 관련해 특별히 걱정되는 점은 보이지 않았어요."
_WATCH = (
    "마음이 많이 힘든 순간이 있었던 것 같아요. "
    "혼자 견디기 어려울 때는 언제든 상담사에게 알려 주세요."
)
_URGENT = (
    "지금 많이 힘든 상태로 보여요. 혼자 있지 말고 가까운 사람이나 "
    "자살예방상담전화 109(24시간)에 바로 연락해 주세요."
)
SAFETY_CHECK: dict[Level, str] = {
    "none": _SAFE,
    "low": _SAFE,
    "medium": _WATCH,
    "high": _URGENT,
    "critical": _URGENT,
}
EMPTY_AFTER_FILTER = "이 항목은 표현을 다듬는 중이에요. 상담사와 함께 확인해 주세요."


class SafetyTracker:
    """필터가 한 번이라도 문장을 지웠는지 모은다 (meta.safety_filtered)."""

    def __init__(self) -> None:
        self.filtered = False

    def text(self, value: str, *, required: bool = False) -> str:
        result = filter_text(value)
        self.filtered = self.filtered or result.filtered
        if required and not result.text:
            return EMPTY_AFTER_FILTER
        return result.text

    def items(self, values: list[str]) -> list[str]:
        return [cleaned for cleaned in (self.text(v) for v in values) if cleaned]


def client_summary_fields(draft: SummaryDraft, tracker: SafetyTracker) -> dict[str, str]:
    return {
        "chief_complaint": tracker.text(draft.chief_complaint, required=True),
        "thinking_patterns": tracker.text(draft.thinking_patterns, required=True),
        "triggers": tracker.text(draft.triggers, required=True),
        "coping": tracker.text(draft.coping, required=True),
    }


def build_client_summary(
    draft: SummaryDraft, level: Level, tracker: SafetyTracker
) -> ClientSummary:
    return ClientSummary(**client_summary_fields(draft, tracker), safety_check=SAFETY_CHECK[level])
