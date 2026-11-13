"""AI-02 매칭 점수."""

from fastapi import APIRouter, Depends

from app.api.deps import ServicesDep, log_call
from app.functions.matching import score_specialties
from app.schemas import SpecialtyScoresRequest, SpecialtyScoresResponse
from app.security import require_internal_token

FN = "score_specialties"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post(
    "/v1/match/specialty-scores",
    response_model=SpecialtyScoresResponse,
    operation_id="AI-02",
    name=FN,
)
async def specialty_scores(
    req: SpecialtyScoresRequest, services: ServicesDep
) -> SpecialtyScoresResponse:
    response = await score_specialties(req, services)
    log_call(FN, response.meta)
    return response
