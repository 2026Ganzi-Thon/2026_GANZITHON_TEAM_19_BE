"""AI-01 인테이크 태깅."""

from fastapi import APIRouter, Depends

from app.api.deps import ServicesDep, cached_or_compute, log_call
from app.functions.tagging import tag_intake
from app.schemas import IntakeTagsResponse, TagIntakeRequest
from app.security import require_internal_token

FN = "tag_intake"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post("/v1/intake/tags", response_model=IntakeTagsResponse, operation_id="AI-01", name=FN)
async def tags(req: TagIntakeRequest, services: ServicesDep) -> IntakeTagsResponse:
    response = await cached_or_compute(
        services, FN, req, IntakeTagsResponse, lambda: tag_intake(req, services)
    )
    log_call(FN, response.meta)
    return response
