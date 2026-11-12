"""연계 리포트 평가 사례 만들기: 합성 내담자 5명 × 3~5회기.

uv run python -m eval.build_referral_cases   # eval/referral_cases.jsonl 갱신

회기별 주제·발화·위험 수준만 표로 두고, 요청 형태(ReferralReportRequest)로 펼친다.
"""

import json
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from app.schemas import ReferralReportRequest

OUTPUT = Path(__file__).resolve().parent / "referral_cases.jsonl"
FIRST_SESSION = date(2026, 10, 5)

# (주제, 내담자 발화, 위험 수준) — 전부 합성 문장
Session = tuple[str, str, str]
CLIENTS: list[tuple[str, list[Session]]] = [
    (
        "취업 준비가 길어지며 불안을 호소",
        [
            ("면접 탈락", "저는 뭘 해도 안 될 것 같아요.", "none"),
            ("가족의 기대", "부모님 얼굴을 볼 면목이 없어요.", "none"),
            ("수면 문제", "누우면 떨어진 면접만 계속 떠올라요.", "low"),
        ],
    ),
    (
        "직장 내 관계와 번아웃",
        [
            ("상사와의 갈등", "팀장님은 저만 미워하는 게 분명해요.", "none"),
            ("야근과 피로", "쉬면 뒤처질 거라서 쉴 수가 없어요.", "none"),
            ("퇴사 고민", "여기서 그만두면 저는 실패자예요.", "none"),
            ("무기력", "다 그만두고 사라지고 싶다는 생각도 했어요.", "high"),
        ],
    ),
    (
        "이별 뒤의 우울감",
        [
            ("이별 직후", "제가 더 잘했으면 안 헤어졌을 거예요.", "none"),
            ("외로움", "이제 저를 좋아해 줄 사람은 없을 거예요.", "none"),
            ("일상 회복", "밥을 챙겨 먹기 시작했어요.", "none"),
        ],
    ),
    (
        "가족 갈등과 자책",
        [
            ("부모의 다툼", "부모님이 싸우는 건 다 제 탓이에요.", "none"),
            ("폭언 피해", "아버지가 화나면 물건을 던져요.", "medium"),
            ("독립 고민", "집을 나가면 저는 나쁜 자식이 되는 거죠.", "none"),
            ("경계 세우기", "싫다고 말해 본 건 처음이에요.", "none"),
            ("지지 자원", "이모한테는 얘기할 수 있을 것 같아요.", "none"),
        ],
    ),
    (
        "대인관계 불안",
        [
            ("모임 회피", "다들 저를 이상하게 볼 게 뻔해요.", "none"),
            ("발표 불안", "한 번 실수하면 다 끝이에요.", "none"),
            ("작은 시도", "먼저 인사를 해 봤는데 괜찮았어요.", "none"),
            ("재발 걱정", "또 예전처럼 돌아갈까 봐 무서워요.", "low"),
        ],
    ),
]


def build_case(intake: str, sessions: list[Session]) -> dict[str, Any]:
    digests = [
        {
            "session_no": number,
            "session_date": (FIRST_SESSION + timedelta(weeks=number - 1)).isoformat(),
            "trainee_label": "수련생 A" if number <= 2 else "수련생 B",
            "digest": {
                "issues": [issue],
                "key_quotes": [quote],
                "mood_shift": "회기 끝에는 처음보다 조금 차분해졌습니다.",
                "risk_level": level,
                "trainee_interventions": ["감정 반영"],
            },
        }
        for number, (issue, quote, level) in enumerate(sessions, start=1)
    ]
    risk_history = [
        {"session_no": number, "level": level, "evidence": quote}
        for number, (_, quote, level) in enumerate(sessions, start=1)
        if level != "none"
    ]
    case = {"digests": digests, "intake_summaries": [intake], "risk_history": risk_history}
    ReferralReportRequest.model_validate(case)
    return case


def main() -> None:
    lines = [json.dumps(build_case(*client), ensure_ascii=False) for client in CLIENTS]
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
