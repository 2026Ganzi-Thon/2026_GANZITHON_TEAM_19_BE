"""TRD 백엔드 v0.5.1에 적힌 값·이름·형태가 코드와 같은지 고정하는 테스트.

장 번호는 TRD 기준이다. 여기서 깨지면 코드가 아니라 TRD와 어긋난 것이다.
"""

import inspect
import io
import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app import export_openapi
from app.config import Settings
from app.functions.masking import MASK_TIMEOUT_SECONDS
from app.functions.ocr import OCR_MAX_BYTES, OCR_TIMEOUT_SECONDS
from app.functions.referral_report import (
    REFERRAL_MAX_TOKENS,
    REFERRAL_TIMEOUT_SECONDS,
    generate_referral_report,
)
from app.functions.risk import RISK_LLM_TIMEOUT_SECONDS, RISK_MAX_TOKENS
from app.functions.session_report import (
    MAX_TRANSCRIPT_TOKENS,
    SESSION_MAX_TOKENS,
    SESSION_TIMEOUT_SECONDS,
)
from app.functions.tagging import TAG_MAX_TOKENS, TAG_TIMEOUT_SECONDS
from app.llm.client import LLMClient, LLMProviderError, get_llm, sniff_media_type
from app.llm.fake import FakeLLMClient
from app.llm.limits import GENERAL_CONCURRENCY, RISK_CONCURRENCY
from app.main import create_app
from app.middleware import JSON_BODY_LIMIT
from app.resources import AI_ROOT, load_dictionary
from app.schemas import Meta
from tests.conftest import HEADERS, PDF_BYTES, PNG_BYTES, ScriptedLLM, make_services, make_settings
from tests.test_reports import REAL_QUOTE, referral_draft, referral_request

REPO_ROOT = AI_ROOT.parent


# --- 16-1 내부 API 계약 ----------------------------------------------------


def test_paths_and_operation_ids_match_the_contract_table() -> None:
    spec = json.loads(export_openapi.render())
    operations = {
        operation["operationId"]: path
        for path, methods in spec["paths"].items()
        for operation in methods.values()
    }

    assert operations == {
        "AI-01": "/v1/intake/tags",
        "AI-02": "/v1/match/specialty-scores",
        "AI-03": "/v1/risk/assess",
        "AI-04": "/v1/reports/session",
        "AI-05": "/v1/reports/referral",
        "AI-06": "/v1/mask",
        "AI-07": "/v1/ocr/certificate",
    }


def test_time_limits_match_the_contract_table() -> None:
    limits = (
        TAG_TIMEOUT_SECONDS,
        RISK_LLM_TIMEOUT_SECONDS,
        SESSION_TIMEOUT_SECONDS,
        REFERRAL_TIMEOUT_SECONDS,
        MASK_TIMEOUT_SECONDS,
        OCR_TIMEOUT_SECONDS,
    )

    assert limits == (5.0, 3.0, 60.0, 90.0, 20.0, 10.0)


def test_meta_has_exactly_the_documented_fields() -> None:
    assert list(Meta.model_fields) == [
        "provider",
        "model",
        "prompt_version",
        "tokens_in",
        "tokens_out",
        "latency_ms",
        "fallback",
        "cache_hit",
        "safety_filtered",
    ]


# --- 16-2 태그 사전 --------------------------------------------------------


def test_dictionary_file_holds_only_the_three_fixed_lists() -> None:
    raw = json.loads((AI_ROOT / "contract" / "dictionary.json").read_text(encoding="utf-8"))

    assert set(raw) == {"version", "emotions", "distortions", "specialties"}
    assert (len(raw["emotions"]), len(raw["distortions"]), len(raw["specialties"])) == (12, 10, 12)


def test_category_codes_map_to_korean_labels_in_code() -> None:
    dictionary = load_dictionary()

    assert dictionary.category_label("career") == "진로·취업"
    assert dictionary.category_label("unknown") == "unknown"


# --- 16-2-1 근거 인용 검증 --------------------------------------------------


async def test_referral_evidence_must_be_a_whole_key_quote_not_a_fragment() -> None:
    draft = referral_draft(
        distortions=[
            {"name": "과잉일반화", "evidence": "안 될 것 같아요", "session_no": 2},
            {"name": "과잉일반화", "evidence": f"“{REAL_QUOTE}”", "session_no": 1},
        ]
    )
    services = make_services(llm=ScriptedLLM(draft))

    report = await generate_referral_report(referral_request(), services)

    assert [(d.evidence, d.session_no) for d in report.distortions] == [(REAL_QUOTE, 2)]


# --- 16-7 인터페이스 --------------------------------------------------------


def test_llm_client_signatures_match_the_interface() -> None:
    complete = inspect.signature(LLMClient.complete_json)
    vision = inspect.signature(LLMClient.vision_json)

    assert list(complete.parameters) == [
        "self",
        "system",
        "user",
        "schema",
        "timeout",
        "max_tokens",
    ]
    assert complete.parameters["max_tokens"].default == 1500
    assert list(vision.parameters) == ["self", "system", "image", "schema", "timeout"]
    assert list(inspect.signature(LLMClient.embed).parameters) == ["self", "texts"]


def test_get_llm_lives_in_client_module_and_returns_fake_by_default() -> None:
    assert get_llm.__module__ == "app.llm.client"
    assert isinstance(get_llm(make_settings()), FakeLLMClient)


def test_risk_without_llm_is_reported_as_fallback(client: TestClient) -> None:
    response = client.post(
        "/v1/risk/assess", json={"text": "유서를 써 두었어요", "use_llm": False}, headers=HEADERS
    )

    assert response.json()["level"] == "critical"
    assert response.json()["meta"]["fallback"] is True


@pytest.mark.parametrize(
    ("data", "expected"),
    [
        (PNG_BYTES, "image/png"),
        (PDF_BYTES, "application/pdf"),
        (bytes([0xFF, 0xD8, 0xFF, 0xE0]) + b"jpeg", "image/jpeg"),
        (b"RIFF0000WEBPVP8 ", None),  # 서류 추출 API가 받지 않는 형식
        (b"plain text", None),
        (b"", None),
    ],
)
def test_file_type_is_detected_from_signature(data: bytes, expected: str | None) -> None:
    assert sniff_media_type(data) == expected


# --- 7-3 환경변수 -----------------------------------------------------------


def test_settings_use_only_the_documented_variable_names() -> None:
    assert set(Settings.model_fields) == {
        "ai_internal_tokens",
        "llm_provider",
        "llm_api_key",
        "llm_model",
        "llm_daily_token_cap",
        "demo_mode",
        "redis_url",
        "fake_llm_latency_ms",
    }


def test_env_example_lists_the_same_variables_and_no_values_for_secrets() -> None:
    lines = (REPO_ROOT / "ai.env.example").read_text(encoding="utf-8").splitlines()
    pairs = dict(line.split("=", 1) for line in lines if line and not line.startswith("#"))

    assert {name.lower() for name in pairs} == set(Settings.model_fields)
    assert pairs["AI_INTERNAL_TOKENS"] == pairs["LLM_API_KEY"] == pairs["LLM_MODEL"] == ""
    assert pairs["LLM_PROVIDER"] == "fake"


def test_real_provider_needs_an_explicit_model() -> None:
    with pytest.raises(ValueError, match="LLM_MODEL"):
        make_settings(llm_provider="upstage", llm_api_key="test-key")


# --- 16-9 출력 상한, 16-11 공통 규약 ---------------------------------------


def test_output_caps_and_limits_match_the_tables() -> None:
    assert (TAG_MAX_TOKENS, RISK_MAX_TOKENS, SESSION_MAX_TOKENS, REFERRAL_MAX_TOKENS) == (
        300,
        100,
        2000,
        2500,
    )
    assert (JSON_BODY_LIMIT, OCR_MAX_BYTES) == (1024 * 1024, 10 * 1024 * 1024)
    assert MAX_TRANSCRIPT_TOKENS == 16_000
    assert (RISK_CONCURRENCY, GENERAL_CONCURRENCY) == (4, 4)


def error_cases() -> list[tuple[Any, int, str, bool]]:
    return [
        (LLMProviderError("status 500"), 502, "AI_PROVIDER_ERROR", True),
        (LLMProviderError("status 400", retryable=False), 502, "AI_PROVIDER_ERROR", True),
    ]


@pytest.mark.parametrize(("raised", "status", "code", "retryable"), error_cases())
def test_provider_errors_follow_the_error_table(
    raised: Exception, status: int, code: str, retryable: bool
) -> None:
    services = make_services(llm=ScriptedLLM(raised))
    with TestClient(create_app(services)) as client:
        response = client.post(
            "/v1/reports/session",
            json={
                "transcript": [{"role": "client", "text": "안녕하세요"}],
                "intake": {"category": "self"},
            },
            headers=HEADERS,
        )

    assert response.status_code == status
    assert response.json()["error"] == {
        "code": code,
        "message": "LLM provider error",
        "retryable": retryable,
    }


def test_every_response_carries_the_budget_header_even_413_and_401(client: TestClient) -> None:
    too_large = client.post("/v1/mask", content=b"x" * (JSON_BODY_LIMIT + 1), headers=HEADERS)
    unauthorized = client.post("/v1/mask", json={"text": "안녕하세요"})
    health = client.get("/health")

    assert too_large.status_code == 413
    assert unauthorized.status_code == 401
    for response in (too_large, unauthorized, health):
        assert "X-AI-Budget-Used" in response.headers


def test_ocr_accepts_pdf_first_page(client: TestClient) -> None:
    response = client.post(
        "/v1/ocr/certificate",
        files={"file": ("certificate.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
        headers=HEADERS,
    )

    assert response.status_code == 200


# --- 16-11 계약 파일, 18-4 이미지, 18-5 CI ----------------------------------


def test_export_openapi_writes_the_contract_to_stdout(
    capsysbinary: pytest.CaptureFixture[bytes],
) -> None:
    export_openapi.main()

    assert capsysbinary.readouterr().out == export_openapi.OPENAPI_PATH.read_bytes()


def test_dockerfile_matches_the_documented_image() -> None:
    dockerfile = (AI_ROOT / "Dockerfile").read_text(encoding="utf-8")
    instructions = [
        line for line in dockerfile.splitlines() if line.strip() and not line.startswith("#")
    ]

    assert instructions == [
        "FROM python:3.12-slim",
        "RUN apt-get update && apt-get install -y --no-install-recommends curl"
        " && rm -rf /var/lib/apt/lists/*",
        "COPY --from=ghcr.io/astral-sh/uv:0.9 /uv /usr/local/bin/uv",
        "WORKDIR /app",
        "COPY ai/pyproject.toml ai/uv.lock ./",
        "RUN uv sync --frozen --no-dev",
        "COPY ai/ .",
        'ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1',
        "RUN useradd -r -u 10002 ai && chown -R ai /app",
        "USER ai",
        "EXPOSE 8090",
        'CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090",'
        ' "--workers", "1", "--no-access-log"]',
    ]


def test_ci_workflow_runs_the_five_documented_steps_in_order() -> None:
    workflow = (REPO_ROOT / ".github" / "workflows" / "ci-ai.yml").read_text(encoding="utf-8")
    steps = [
        "uv sync --frozen",
        "uv run ruff check .",
        "uv run ruff format --check .",
        "uv run mypy app/functions app/llm",
        "uv run pytest",
        "uv run python -m app.export_openapi > /tmp/ai-openapi.json",
        "oasdiff breaking",
    ]

    positions = [workflow.index(step) for step in steps]
    assert positions == sorted(positions)
    assert "ai-breaking" in workflow
    assert '- "ai/**"' in workflow


def test_makefile_has_the_documented_ai_targets() -> None:
    makefile = (REPO_ROOT / "Makefile").read_text(encoding="utf-8")

    for target, command in {
        "ai-openapi:": "python -m app.export_openapi > openapi.json",
        "ai-eval:": "python -m eval.run --suite all",
        "ai-demo-cache:": "python -m app.demo_cache.build",
    }.items():
        assert target in makefile
        assert command in makefile


def test_api_folder_has_one_file_per_endpoint() -> None:
    api_files = {path.name for path in Path(AI_ROOT / "app" / "api").glob("*.py")}

    assert api_files == {
        "__init__.py",
        "deps.py",
        "tagging.py",
        "matching.py",
        "risk.py",
        "session_report.py",
        "referral_report.py",
        "masking.py",
        "ocr.py",
    }
