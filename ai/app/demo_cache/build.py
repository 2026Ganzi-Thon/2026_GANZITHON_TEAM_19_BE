"""시연 캐시 만들기 (TRD 16-8).

uv run python -m app.demo_cache.build

실 LLM(환경변수 LLM_PROVIDER)으로 시연 시나리오 입력의 응답을 미리 만들어
app/demo_cache/<함수>/<입력 해시>.json에 둔다. 만든 뒤에는 팀이 내용을 검수하고
PR에 올린다. 프롬프트 버전을 바꾸면 다시 만든다.

입력은 app/demo_cache/inputs/<함수>/*.json (요청 본문 그대로)에서 읽는다.
백엔드 시드(C-03 고민 글 3개, 시드 대화, 위험 문장 3개)와 글자 하나까지 같아야 한다.
"""

import asyncio
import json
import sys
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from app.config import get_settings
from app.demo_cache.store import CACHE_ROOT, DemoCache, input_hash, save_entry
from app.functions.referral_report import generate_referral_report
from app.functions.risk import assess_risk
from app.functions.session_report import generate_session_report
from app.functions.tagging import tag_intake
from app.logging import configure_logging, get_logger
from app.schemas import (
    ReferralReportRequest,
    RiskRequest,
    SessionReportRequest,
    TagIntakeRequest,
)
from app.services import Services, build_services

INPUTS_DIR = CACHE_ROOT / "inputs"
Handler = Callable[[Any, Services], Awaitable[BaseModel]]
TARGETS: dict[str, tuple[type[BaseModel], Handler]] = {
    "tag_intake": (TagIntakeRequest, tag_intake),
    "assess_risk": (RiskRequest, assess_risk),
    "generate_session_report": (SessionReportRequest, generate_session_report),
    "generate_referral_report": (ReferralReportRequest, generate_referral_report),
}

log = get_logger()


async def build(services: Services, inputs_dir: Path = INPUTS_DIR) -> int:
    written = 0
    for fn, (request_model, handler) in TARGETS.items():
        for path in sorted((inputs_dir / fn).glob("*.json")):
            request = request_model.model_validate(json.loads(path.read_text(encoding="utf-8")))
            response = await handler(request, services)
            save_entry(fn, input_hash(request), response)
            written += 1
            log.info("demo_cache_built", fn=fn, source=path.name)
    return written


async def main() -> int:
    configure_logging()
    settings = get_settings()
    if settings.llm_provider == "fake":
        log.error("demo_cache_refused", reason="LLM_PROVIDER=fake")
        return 1
    # 캐시를 읽지 않고 항상 실 LLM을 부른다.
    services = build_services(settings, demo_cache=DemoCache({}, enabled=False))
    try:
        written = await build(services)
    finally:
        await services.close()
    log.info("demo_cache_done", entries=written)
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
