"""내부 API 7개와 공통 규약 (인증·오류·크기·헤더·로그) 통합 테스트."""

import io
import json

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.services import Services
from tests.conftest import HEADERS, PNG_BYTES, make_services

SECRET_PHRASE = "비밀문장-절대로그금지"
TRANSCRIPT = [
    {"role": "provider", "text": "오늘은 어떤 이야기를 하고 싶으세요?"},
    {"role": "client", "text": "면접에서 또 떨어졌어요. 저는 뭘 해도 안 될 것 같아요."},
    {"role": "provider", "text": "많이 지치셨겠어요."},
    {"role": "client", "text": "연락은 010-1234-5678로 주세요. 다 망한 것 같아요."},
]


def test_health_needs_no_token(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/ready").status_code == 200


@pytest.mark.parametrize("headers", [{}, {"X-Internal-Token": "wrong-token"}])
def test_v1_rejects_missing_or_wrong_token(client: TestClient, headers: dict[str, str]) -> None:
    response = client.post("/v1/risk/assess", json={"text": "안녕하세요"}, headers=headers)

    assert response.status_code == 401
    assert response.json()["error"] == {
        "code": "AI_UNAUTHORIZED",
        "message": "invalid internal token",
        "retryable": False,
    }


def test_tag_intake_returns_only_dictionary_tags(client: TestClient, services: Services) -> None:
    response = client.post(
        "/v1/intake/tags",
        json={"category": "career", "text": "취업 준비가 길어져서 불안하고 다 망한 것 같아요"},
        headers=HEADERS,
    )

    body = response.json()
    assert response.status_code == 200
    assert set(body["emotions"]) <= set(services.dictionary.emotions)
    assert set(body["distortions"]) <= set(services.dictionary.distortions)
    assert body["meta"]["prompt_version"] == "tag_intake.v1"
    assert body["meta"]["provider"] == "fake"


def test_tag_intake_rejects_text_over_2000_chars(client: TestClient) -> None:
    response = client.post(
        "/v1/intake/tags", json={"category": "career", "text": "가" * 2001}, headers=HEADERS
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "AI_BAD_REQUEST"


def test_validation_error_does_not_echo_input(client: TestClient) -> None:
    response = client.post(
        "/v1/risk/assess", json={"text": SECRET_PHRASE, "context": "not-a-list"}, headers=HEADERS
    )

    assert response.status_code == 422
    assert SECRET_PHRASE not in response.text
    assert "not-a-list" not in response.text


def test_score_specialties_ranks_matching_tag_higher(client: TestClient) -> None:
    response = client.post(
        "/v1/match/specialty-scores",
        json={
            "keywords": ["취업 스트레스", "면접 불안"],
            "category": "career",
            "candidates": [
                {"id": "p1", "tags": ["취업 스트레스", "청년 진로"]},
                {"id": "p2", "tags": ["가족 관계"]},
                {"id": "p3", "tags": []},
            ],
        },
        headers=HEADERS,
    )

    scores = response.json()["scores"]
    assert scores["p1"] == 1.0
    assert 0.0 <= scores["p2"] < scores["p1"]
    assert scores["p3"] == 0.0


def test_assess_risk_keeps_rule_level_when_llm_says_none(client: TestClient) -> None:
    response = client.post(
        "/v1/risk/assess", json={"text": "요즘은 그냥 죽고 싶어요", "context": []}, headers=HEADERS
    )

    body = response.json()
    assert body["level"] == "high"
    assert body["rule_hits"] == ["suicidal_ideation"]
    assert body["llm_score"] == 0.05
    assert body["meta"]["fallback"] is False


def test_assess_risk_without_llm_returns_rule_only(client: TestClient) -> None:
    response = client.post(
        "/v1/risk/assess", json={"text": "유서를 써 두었어요", "use_llm": False}, headers=HEADERS
    )

    body = response.json()
    assert body["level"] == "critical"
    assert body["llm_score"] is None


def test_session_report_masks_pii_and_verifies_quotes(client: TestClient) -> None:
    response = client.post(
        "/v1/reports/session",
        json={"transcript": TRANSCRIPT, "intake": {"category": "career"}},
        headers=HEADERS,
    )

    body = response.json()
    assert response.status_code == 200
    assert "010-1234-5678" not in response.text
    assert body["digest"]["key_quotes"]
    assert all(d["evidence"] in " ".join(body["digest"]["key_quotes"]) for d in body["distortions"])
    assert body["client_summary"]["safety_check"]
    assert body["red_flag"]["level"] == body["digest"]["risk_level"] == "none"


def test_referral_report_covers_every_session_and_uses_history(client: TestClient) -> None:
    digest = {
        "issues": ["면접 탈락"],
        "key_quotes": ["저는 뭘 해도 안 될 것 같아요."],
        "mood_shift": "조금 가라앉았다",
        "risk_level": "none",
        "trainee_interventions": ["감정 반영"],
    }
    response = client.post(
        "/v1/reports/referral",
        json={
            "digests": [
                {
                    "session_no": 1,
                    "session_date": "2026-11-01",
                    "trainee_label": "수련생 A",
                    "digest": digest,
                },
                {
                    "session_no": 2,
                    "session_date": "2026-11-08",
                    "trainee_label": "수련생 B",
                    "digest": digest,
                },
            ],
            "intake_summaries": ["취업 준비 스트레스"],
            "risk_history": [{"session_no": 2, "level": "high", "evidence": "죽고 싶어요"}],
        },
        headers=HEADERS,
    )

    body = response.json()
    assert response.status_code == 200
    assert [t["session_no"] for t in body["timeline"]] == [1, 2]
    assert body["timeline"][1]["trainee_label"] == "수련생 B"
    assert body["red_flag"]["max_level"] == "high"
    assert body["red_flag"]["history"] == [
        {"session_no": 1, "level": "none", "evidence": None},
        {"session_no": 2, "level": "high", "evidence": "죽고 싶어요"},
    ]
    assert all(d["evidence"] in digest["key_quotes"] for d in body["distortions"])
    assert "109" in body["client_summary"]["safety_check"]


def test_mask_replaces_regex_and_named_entities(client: TestClient) -> None:
    response = client.post(
        "/v1/mask",
        json={"text": "민수씨가 010-1234-5678로 전화했어요. 민수씨는 화가 났어요."},
        headers=HEADERS,
    )

    body = response.json()
    assert body["text"] == "[이름1]씨가 [연락처1]로 전화했어요. [이름1]씨는 화가 났어요."
    assert {e["token"] for e in body["entities"]} == {"[이름1]", "[연락처1]"}
    assert body["partial"] is False


def test_ocr_reads_certificate_fields(client: TestClient) -> None:
    response = client.post(
        "/v1/ocr/certificate",
        files={"file": ("cert.png", io.BytesIO(PNG_BYTES), "image/png")},
        headers=HEADERS,
    )

    body = response.json()
    assert response.status_code == 200
    assert body["issued_on"] == "2026-03-02"
    assert body["cert_number"] == "2026-0001"


def test_ocr_rejects_unsupported_file_type(client: TestClient) -> None:
    response = client.post(
        "/v1/ocr/certificate",
        files={"file": ("cert.png", io.BytesIO(b"plain text"), "image/png")},
        headers=HEADERS,
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "AI_BAD_REQUEST"


def test_json_body_over_1mb_is_rejected(client: TestClient) -> None:
    response = client.post(
        "/v1/mask", content=json.dumps({"text": "가" * 600_000}), headers=HEADERS
    )

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "AI_PAYLOAD_TOO_LARGE"


def test_responses_carry_request_id_and_budget_header(client: TestClient) -> None:
    response = client.post(
        "/v1/risk/assess",
        json={"text": "안녕하세요"},
        headers={**HEADERS, "X-Request-ID": "job:1234"},
    )

    assert response.headers["X-Request-ID"] == "job:1234"
    assert 0.0 <= float(response.headers["X-AI-Budget-Used"]) <= 1.0


def test_budget_exhausted_returns_429_but_risk_still_answers_200() -> None:
    services = make_services(llm_daily_token_cap=10)
    with TestClient(create_app(services)) as client:
        client.post(
            "/v1/intake/tags", json={"category": "career", "text": "불안해요"}, headers=HEADERS
        )
        tags = client.post(
            "/v1/intake/tags", json={"category": "career", "text": "불안해요"}, headers=HEADERS
        )
        risk = client.post("/v1/risk/assess", json={"text": "죽고 싶어요"}, headers=HEADERS)

    assert tags.status_code == 429
    assert tags.json()["error"] == {
        "code": "AI_BUDGET_EXCEEDED",
        "message": "daily token cap reached",
        "retryable": False,
    }
    assert tags.headers["X-AI-Budget-Used"] == "1.000"
    assert risk.status_code == 200
    assert risk.json()["level"] == "high"
    assert risk.json()["meta"]["fallback"] is True


def test_logs_never_contain_request_or_response_bodies(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    client.post("/v1/mask", json={"text": f"{SECRET_PHRASE} 민수씨"}, headers=HEADERS)
    client.post(
        "/v1/reports/session",
        json={
            "transcript": [{"role": "client", "text": f"{SECRET_PHRASE} 죽고 싶어요"}],
            "intake": {"category": "self", "text": SECRET_PHRASE},
        },
        headers=HEADERS,
    )
    client.post("/v1/risk/assess", json={"text": SECRET_PHRASE, "context": 1}, headers=HEADERS)

    logs = capsys.readouterr().out
    assert '"event": "ai_call"' in logs
    assert SECRET_PHRASE not in logs
    assert "죽고 싶어요" not in logs
