"""AI-05 연계 종합 리포트."""

from fastapi import APIRouter, Depends

from app.api.deps import ServicesDep, cached_or_compute, log_call
from app.functions.referral_report import generate_referral_report
from app.schemas import ReferralReportRequest, ReferralReportResponse
from app.security import require_internal_token

FN = "generate_referral_report"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post(
    "/v1/reports/referral", response_model=ReferralReportResponse, operation_id="AI-05", name=FN
)
async def referral(req: ReferralReportRequest, services: ServicesDep) -> ReferralReportResponse:
    response = await cached_or_compute(
        services,
        FN,
        req,
        ReferralReportResponse,
        lambda: generate_referral_report(req, services),
    )
    log_call(FN, response.meta)
    return response
