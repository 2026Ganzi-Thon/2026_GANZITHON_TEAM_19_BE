"""프롬프트·모델 평가 (TRD 부록 B-4).

uv run python -m eval.run --suite all      # risk | tagging | masking | referral | all
uv run python -m eval.run --suite risk --rules-only

환경변수 LLM_PROVIDER로 돈다 (fake면 배선 확인용, 실 LLM이면 실제 품질 측정).
결과는 eval/results/<날짜>_<프롬프트 버전>.json에 점수만 쓴다. 입력 원문은 쓰지 않는다.
"""

import argparse
import asyncio
import json
import statistics
import sys
import time
from collections.abc import Awaitable, Callable
from datetime import datetime
from pathlib import Path
from typing import Any

from app.config import get_settings
from app.demo_cache.store import DemoCache
from app.functions.masking import MASK_TIMEOUT_SECONDS, mask_pii
from app.functions.referral_report import (
    REFERRAL_TIMEOUT_SECONDS,
    generate_referral_report,
)
from app.functions.risk import RISK_LLM_TIMEOUT_SECONDS, assess_risk
from app.functions.tagging import TAG_TIMEOUT_SECONDS, tag_intake
from app.llm.budget import KST
from app.logging import configure_logging
from app.schemas import (
    MaskRequest,
    ReferralReportRequest,
    RiskRequest,
    TagIntakeRequest,
    level_index,
    max_level,
)
from app.services import Services, build_services

EVAL_DIR = Path(__file__).resolve().parent
RESULTS_DIR = EVAL_DIR / "results"
HIGH = level_index("high")
RISK_RECALL_GATE = 0.9  # 간지톤 기준. 운영 전환은 0.95
TAGGING_GATE = 0.7
ENTITY_LEAK_GATE = 0.10
LATENCY_GATE_RATIO = 0.7  # 함수별 p90이 16-1 제한 시간의 70% 이하
TOP_EMOTIONS = 3
# 평가 스위트 → 그 스위트가 재는 프롬프트
SUITE_PROMPTS = {
    "risk": "assess_risk",
    "tagging": "tag_intake",
    "masking": "mask_pii",
    "referral": "referral_report",
}


def load(name: str) -> list[dict[str, Any]]:
    lines = (EVAL_DIR / name).read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]


def p90(values: list[float]) -> float:
    if len(values) < 2:
        return values[0] if values else 0.0
    return statistics.quantiles(values, n=10)[8]


async def timed[T](call: Awaitable[T]) -> tuple[T, float]:
    started = time.perf_counter()
    result = await call
    return result, time.perf_counter() - started


def latency(seconds: list[float], limit: float) -> dict[str, Any]:
    value = p90(seconds)
    return {"p90_seconds": round(value, 3), "passed": value <= limit * LATENCY_GATE_RATIO}


async def eval_risk(services: Services, *, use_llm: bool) -> dict[str, Any]:
    cases = load("risk_cases.jsonl")
    seconds: list[float] = []
    found = missed = false_alarms = fallbacks = 0
    for case in cases:
        request = RiskRequest(text=case["text"], use_llm=use_llm)
        response, elapsed = await timed(assess_risk(request, services))
        seconds.append(elapsed)
        fallbacks += response.meta.fallback
        expected_high = level_index(case["level"]) >= HIGH
        predicted_high = level_index(response.level) >= HIGH
        found += expected_high and predicted_high
        missed += expected_high and not predicted_high
        false_alarms += predicted_high and not expected_high
    recall = found / (found + missed)
    return {
        "cases": len(cases),
        "high_or_above": found + missed,
        "recall_high": round(recall, 3),
        "false_alarms": false_alarms,
        "llm_fallbacks": fallbacks,
        "latency": latency(seconds, RISK_LLM_TIMEOUT_SECONDS),
        "passed": recall >= RISK_RECALL_GATE,
    }


async def eval_tagging(services: Services) -> dict[str, Any]:
    cases = load("tagging_cases.jsonl")
    seconds: list[float] = []
    matched = 0
    for case in cases:
        request = TagIntakeRequest(category=case["category"], text=case["text"])
        response, elapsed = await timed(tag_intake(request, services))
        seconds.append(elapsed)
        matched += bool(set(response.emotions[:TOP_EMOTIONS]) & set(case["emotions"]))
    agreement = matched / len(cases)
    return {
        "cases": len(cases),
        "emotion_agreement": round(agreement, 3),
        "latency": latency(seconds, TAG_TIMEOUT_SECONDS),
        "passed": agreement >= TAGGING_GATE,
    }


async def eval_masking(services: Services) -> dict[str, Any]:
    cases = load("masking_cases.jsonl")
    seconds: list[float] = []
    regex_total = regex_leaks = entity_total = entity_leaks = 0
    for case in cases:
        request = MaskRequest(text=case["text"])
        response, elapsed = await timed(mask_pii(request, services))
        seconds.append(elapsed)
        regex_total += len(case["regex"])
        regex_leaks += sum(value in response.text for value in case["regex"])
        entity_total += len(case["entities"])
        entity_leaks += sum(value in response.text for value in case["entities"])
    entity_rate = entity_leaks / entity_total if entity_total else 0.0
    return {
        "cases": len(cases),
        "regex_leak_rate": round(regex_leaks / regex_total, 3) if regex_total else 0.0,
        "entity_leak_rate": round(entity_rate, 3),
        "latency": latency(seconds, MASK_TIMEOUT_SECONDS),
        "passed": regex_leaks == 0 and entity_rate <= ENTITY_LEAK_GATE,
    }


async def eval_referral(services: Services) -> dict[str, Any]:
    cases = load("referral_cases.jsonl")
    seconds: list[float] = []
    missing_sessions = evidence_total = evidence_ok = risk_ok = 0
    for case in cases:
        request = ReferralReportRequest.model_validate(case)
        response, elapsed = await timed(generate_referral_report(request, services))
        seconds.append(elapsed)
        # ① 포함 회기의 주요 호소가 빠지지 않는지
        covered = {item.session_no for item in response.timeline if item.key_points}
        missing_sessions += len({entry.session_no for entry in request.digests} - covered)
        # ② evidence가 모두 원문 key_quotes에 있는지
        quotes = {quote for entry in request.digests for quote in entry.digest.key_quotes}
        evidence_total += len(response.distortions)
        evidence_ok += sum(item.evidence in quotes for item in response.distortions)
        # ③ 위험 이력이 회기별 최댓값과 같은지
        expected = max_level(
            *(entry.digest.risk_level for entry in request.digests),
            *(item.level for item in request.risk_history),
        )
        risk_ok += response.red_flag.max_level == expected
    evidence_rate = evidence_ok / evidence_total if evidence_total else 1.0
    return {
        "cases": len(cases),
        "missing_sessions": missing_sessions,
        "evidence_in_quotes": round(evidence_rate, 3),
        "risk_history_match": round(risk_ok / len(cases), 3),
        "latency": latency(seconds, REFERRAL_TIMEOUT_SECONDS),
        "passed": missing_sessions == 0 and evidence_rate == 1.0 and risk_ok == len(cases),
    }


async def run(suite: str, *, rules_only: bool) -> dict[str, Any]:
    settings = get_settings()
    services = build_services(settings, demo_cache=DemoCache({}, enabled=False))
    suites: dict[str, Callable[[], Awaitable[dict[str, Any]]]] = {
        "risk": lambda: eval_risk(services, use_llm=not rules_only),
        "tagging": lambda: eval_tagging(services),
        "masking": lambda: eval_masking(services),
        "referral": lambda: eval_referral(services),
    }
    selected = list(suites) if suite == "all" else [suite]
    try:
        results = {name: await suites[name]() for name in selected}
    finally:
        await services.close()
    return {
        "provider": services.llm.provider,
        "model": services.llm.model,
        "rules_only": rules_only,
        "prompt_versions": {
            suite: services.prompts[name].version for suite, name in SUITE_PROMPTS.items()
        },
        "suites": results,
        "passed": all(result["passed"] for result in results.values()),
    }


def write_results(report: dict[str, Any]) -> list[Path]:
    """스위트마다 eval/results/<날짜>_<프롬프트 버전>.json (TRD 부록 B-4).

    점수만 쓰고 입력 원문은 쓰지 않는다. PR에 첨부한다.
    """
    RESULTS_DIR.mkdir(exist_ok=True)
    today = datetime.now(KST).strftime("%Y-%m-%d")
    paths: list[Path] = []
    for suite, scores in report["suites"].items():
        path = RESULTS_DIR / f"{today}_{report['prompt_versions'][suite]}.json"
        body = {
            "provider": report["provider"],
            "model": report["model"],
            "rules_only": report["rules_only"],
            suite: scores,
        }
        path.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        paths.append(path)
    return paths


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--suite", default="all", choices=["all", "risk", "tagging", "masking", "referral"]
    )
    parser.add_argument("--rules-only", action="store_true", help="위험 판정을 규칙만으로 측정")
    args = parser.parse_args()
    configure_logging()
    report = asyncio.run(run(args.suite, rules_only=args.rules_only))
    paths = write_results(report)
    sys.stdout.write(json.dumps(report["suites"], ensure_ascii=False, indent=2) + "\n")
    for path in paths:
        sys.stdout.write(f"result file: {path}\n")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
