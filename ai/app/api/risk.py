"""AI-03 Red Flag 판정 (TRD 16-3, 16-7)."""

from fastapi import APIRouter, Depends

from app.api.deps import ServicesDep, cached_or_compute, log_call
from app.functions.risk import assess_risk, masked_evidence
from app.schemas import RiskRequest, RiskResponse, max_level
from app.security import require_internal_token

FN = "assess_risk"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post("/v1/risk/assess", response_model=RiskResponse, operation_id="AI-03", name=FN)
async def assess(req: RiskRequest, services: ServicesDep) -> RiskResponse:
    response = await cached_or_compute(
        services, FN, req, RiskResponse, lambda: assess_risk(req, services)
    )
    if response.meta.cache_hit:
        # 규칙 필터는 캐시와 관계없이 항상 돌린다 (16-8). 캐시가 규칙보다 낮출 수 없다.
        rule = services.risk_engine.check(req.text)
        response = response.model_copy(
            update={
                "level": max_level(rule.level, response.level),
                "rule_hits": list(rule.hits),
                "evidence": response.evidence or masked_evidence(req.text, rule, services.masker),
            }
        )
    log_call(FN, response.meta)
    return response
