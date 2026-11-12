"""AI-06 PII 마스킹."""

from fastapi import APIRouter, Depends

from app.api.deps import ServicesDep, log_call
from app.functions.masking import mask_pii
from app.schemas import MaskRequest, MaskResponse
from app.security import require_internal_token

FN = "mask_pii"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post("/v1/mask", response_model=MaskResponse, operation_id="AI-06", name=FN)
async def mask(req: MaskRequest, services: ServicesDep) -> MaskResponse:
    response = await mask_pii(req, services)
    log_call(FN, response.meta)
    return response
