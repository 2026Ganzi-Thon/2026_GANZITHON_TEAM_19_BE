"""내부 계약: 예시 파일, openapi.json, 시연 캐시가 코드와 어긋나지 않는지 본다."""

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app.demo_cache.store import DemoCache, input_hash, save_entry
from app.export_examples import EXAMPLES_DIR, REQUESTS, build_examples
from app.export_openapi import OPENAPI_PATH, render
from app.main import create_app
from app.resources import PROMPT_NAMES, load_prompts
from app.schemas import (
    IntakeTagsResponse,
    MaskRequest,
    MaskResponse,
    OcrResponse,
    ReferralReportRequest,
    ReferralReportResponse,
    RiskRequest,
    RiskResponse,
    SessionReportRequest,
    SessionReportResponse,
    SpecialtyScoresRequest,
    SpecialtyScoresResponse,
    TagIntakeRequest,
)
from app.services import build_services
from tests.conftest import HEADERS, make_settings

MODELS: dict[str, tuple[type[BaseModel] | None, type[BaseModel]]] = {
    "AI-01": (TagIntakeRequest, IntakeTagsResponse),
    "AI-02": (SpecialtyScoresRequest, SpecialtyScoresResponse),
    "AI-03": (RiskRequest, RiskResponse),
    "AI-04": (SessionReportRequest, SessionReportResponse),
    "AI-05": (ReferralReportRequest, ReferralReportResponse),
    "AI-06": (MaskRequest, MaskResponse),
    "AI-07": (None, OcrResponse),
}


def read_json(path: Path) -> dict[str, Any]:
    data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return data


@pytest.mark.parametrize("api_id", sorted(MODELS))
def test_committed_examples_pass_pydantic_models(api_id: str) -> None:
    request_model, response_model = MODELS[api_id]

    if request_model is not None:
        request_model.model_validate(read_json(EXAMPLES_DIR / f"{api_id}.request.json"))
    response_model.model_validate(read_json(EXAMPLES_DIR / f"{api_id}.response.json"))


def test_committed_examples_match_what_the_server_returns() -> None:
    for name, expected in build_examples().items():
        assert read_json(EXAMPLES_DIR / name) == expected, f"{name}: python -m app.export_examples"


def test_committed_openapi_is_up_to_date() -> None:
    assert OPENAPI_PATH.read_text(encoding="utf-8") == render(), "make ai-openapi"


def test_openapi_lists_seven_operations() -> None:
    spec = json.loads(render())
    operation_ids = sorted(
        operation["operationId"] for path in spec["paths"].values() for operation in path.values()
    )

    assert operation_ids == [f"AI-0{n}" for n in range(1, 8)]


def test_every_function_has_a_prompt_file() -> None:
    prompts = load_prompts()

    assert set(prompts) >= set(PROMPT_NAMES)
    assert prompts["tag_intake"].version == "tag_intake.v1"
    assert "<user_text>" in prompts["session_report"].text


def demo_client(tmp_path: Path) -> TestClient:
    settings = make_settings(demo_mode="on")
    cache = DemoCache.load(enabled=True, root=tmp_path)
    return TestClient(create_app(build_services(settings, demo_cache=cache)))


def test_demo_mode_serves_reviewed_cache_on_exact_input_match(tmp_path: Path) -> None:
    _, payload = REQUESTS["AI-01"]
    reviewed = IntakeTagsResponse.model_validate(read_json(EXAMPLES_DIR / "AI-01.response.json"))
    reviewed = reviewed.model_copy(update={"keywords": ["검수된 키워드"]})
    save_entry("tag_intake", input_hash(TagIntakeRequest(**payload)), reviewed, root=tmp_path)

    with demo_client(tmp_path) as client:
        hit = client.post("/v1/intake/tags", json=payload, headers=HEADERS).json()
        miss = client.post(
            "/v1/intake/tags", json={**payload, "text": payload["text"] + " "}, headers=HEADERS
        ).json()

    assert hit["keywords"] == ["검수된 키워드"]
    assert hit["meta"]["cache_hit"] is True
    assert miss["meta"]["cache_hit"] is False


def test_demo_cache_cannot_lower_rule_based_risk(tmp_path: Path) -> None:
    payload = {"text": "유서를 써 두었어요", "context": [], "use_llm": True}
    cached = RiskResponse.model_validate(read_json(EXAMPLES_DIR / "AI-03.response.json"))
    cached = cached.model_copy(update={"level": "none", "rule_hits": [], "evidence": None})
    save_entry("assess_risk", input_hash(RiskRequest(**payload)), cached, root=tmp_path)

    with demo_client(tmp_path) as client:
        body = client.post("/v1/risk/assess", json=payload, headers=HEADERS).json()

    assert body["meta"]["cache_hit"] is True
    assert body["level"] == "critical"
    assert body["rule_hits"] == ["imminent_suicide"]


def test_demo_cache_is_ignored_when_demo_mode_is_off(tmp_path: Path) -> None:
    cache = DemoCache.load(enabled=False, root=tmp_path)

    assert len(cache) == 0
    assert cache.get("tag_intake", "0" * 64) is None
