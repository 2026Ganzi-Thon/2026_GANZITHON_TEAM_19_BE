"""AI-04 세션 리포트."""

from fastapi import APIRouter, Depends

from app.api.deps import ServicesDep, cached_or_compute, log_call
from app.functions.session_report import generate_session_report
from app.schemas import SessionReportRequest, SessionReportResponse
from app.security import require_internal_token

FN = "generate_session_report"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post(
    "/v1/reports/session", response_model=SessionReportResponse, operation_id="AI-04", name=FN
)
async def session(req: SessionReportRequest, services: ServicesDep) -> SessionReportResponse:
    response = await cached_or_compute(
        services, FN, req, SessionReportResponse, lambda: generate_session_report(req, services)
    )
    log_call(FN, response.meta)
    return response
