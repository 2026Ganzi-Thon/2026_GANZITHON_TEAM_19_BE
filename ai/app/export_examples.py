"""내부 계약 예시 ai/contract/examples/AI-0N.{request,response}.json 생성.

python -m app.export_examples

fake LLM으로 실제 엔드포인트를 불러서 만든다. 백엔드는 이 파일로 Java DTO 역직렬화와
WireMock 스텁을 검사한다 (TRD 16-11, 16-12). 예시 문장은 전부 합성이다.
"""

import io
import json
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

from app.config import Settings
from app.llm.budget import MemoryTokenBudget
from app.main import create_app
from app.resources import AI_ROOT
from app.services import build_services

EXAMPLES_DIR = AI_ROOT / "contract" / "examples"
EXAMPLE_TOKEN = "example-internal-token-not-a-real-secret"  # noqa: S105 - 예시 생성용 고정값
# 파일 서명만 맞춘 예시 이미지 (fake LLM은 내용을 읽지 않는다)
EXAMPLE_PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + b"example"
OCR_NOTE = {
    "content_type": "multipart/form-data",
    "fields": {"file": "서류 이미지 또는 PDF 첫 페이지 (10MB 이하)"},
}

_DIGEST_1 = {
    "issues": ["면접 탈락", "자기 비난"],
    "key_quotes": ["저는 뭘 해도 안 될 것 같아요."],
    "mood_shift": "이야기를 하면서 조금 차분해졌습니다.",
    "risk_level": "none",
    "trainee_interventions": ["감정 반영"],
}
_DIGEST_2 = {
    "issues": ["수면 문제", "가족의 기대"],
    "key_quotes": ["부모님 얼굴을 볼 면목이 없어요."],
    "mood_shift": "초반보다 말수가 늘었습니다.",
    "risk_level": "low",
    "trainee_interventions": ["상황 구체화 질문", "강점 탐색"],
}

REQUESTS: dict[str, tuple[str, dict[str, Any]]] = {
    "AI-01": (
        "/v1/intake/tags",
        {
            "category": "career",
            "text": "취업 준비가 1년째인데 면접에서 계속 떨어져요. 불안하고 다 망한 것 같아요.",
        },
    ),
    "AI-02": (
        "/v1/match/specialty-scores",
        {
            "keywords": ["취업 스트레스", "면접 불안"],
            "category": "career",
            "candidates": [
                {"id": "provider-1", "tags": ["취업 스트레스", "청년 진로"]},
                {"id": "provider-2", "tags": ["가족 관계", "상실·애도"]},
            ],
        },
    ),
    "AI-03": (
        "/v1/risk/assess",
        {
            "text": "요즘은 그냥 다 그만두고 사라지고 싶어요.",
            "context": ["면접에서 또 떨어졌어요.", "많이 지치셨겠어요."],
            "use_llm": True,
        },
    ),
    "AI-04": (
        "/v1/reports/session",
        {
            "transcript": [
                {"role": "provider", "text": "오늘은 어떤 이야기를 하고 싶으세요?"},
                {"role": "client", "text": "면접에서 또 떨어졌어요. 저는 뭘 해도 안 될 것 같아요."},
                {"role": "provider", "text": "여러 번 이어지니 많이 지치셨겠어요."},
                {"role": "client", "text": "네. 부모님 얼굴을 볼 면목이 없어요."},
            ],
            "intake": {
                "category": "career",
                "text": "취업 준비가 1년째인데 면접에서 계속 떨어져요.",
                "tags": {"emotions": ["불안"], "distortions": ["파국화"], "keywords": ["취업"]},
            },
        },
    ),
    "AI-05": (
        "/v1/reports/referral",
        {
            "digests": [
                {
                    "session_no": 1,
                    "session_date": "2026-11-01",
                    "trainee_label": "수련생 A",
                    "digest": _DIGEST_1,
                },
                {
                    "session_no": 2,
                    "session_date": "2026-11-08",
                    "trainee_label": "수련생 A",
                    "digest": _DIGEST_2,
                },
            ],
            "intake_summaries": ["취업 준비가 길어지며 불안과 자기 비난을 호소"],
            "risk_history": [{"session_no": 2, "level": "low", "evidence": None}],
        },
    ),
    "AI-06": (
        "/v1/mask",
        {"text": "민수씨가 010-1234-5678로 연락했고, 메일은 minsu@example.com 이라고 했어요."},
    ),
}


def _write(path: Path, data: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )


def _stable(body: dict[str, Any]) -> dict[str, Any]:
    """실행마다 달라지는 값(지연 시간)은 고정한다."""
    return {**body, "meta": {**body["meta"], "latency_ms": 0}}


def build_examples() -> dict[str, dict[str, Any]]:
    settings = Settings(ai_internal_tokens=EXAMPLE_TOKEN, llm_provider="fake")
    services = build_services(settings, budget=MemoryTokenBudget(settings.llm_daily_token_cap))
    headers = {"X-Internal-Token": EXAMPLE_TOKEN}
    files: dict[str, dict[str, Any]] = {}
    with TestClient(create_app(services)) as client:
        for api_id, (path, payload) in REQUESTS.items():
            response = client.post(path, json=payload, headers=headers)
            response.raise_for_status()
            files[f"{api_id}.request.json"] = payload
            files[f"{api_id}.response.json"] = _stable(response.json())
        ocr = client.post(
            "/v1/ocr/certificate",
            files={"file": ("certificate.png", io.BytesIO(EXAMPLE_PNG), "image/png")},
            headers=headers,
        )
        ocr.raise_for_status()
        files["AI-07.request.json"] = OCR_NOTE
        files["AI-07.response.json"] = _stable(ocr.json())
    return files


def main() -> None:
    EXAMPLES_DIR.mkdir(parents=True, exist_ok=True)
    for name, data in build_examples().items():
        _write(EXAMPLES_DIR / name, data)


if __name__ == "__main__":
    main()
