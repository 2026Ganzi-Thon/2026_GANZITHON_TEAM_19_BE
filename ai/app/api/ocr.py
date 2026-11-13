"""AI-07 학회 서류 OCR (multipart file)."""

from typing import Annotated

from fastapi import APIRouter, Depends, File, UploadFile

from app.api.deps import ServicesDep, cached_or_compute, log_call
from app.errors import payload_too_large
from app.functions.ocr import OCR_MAX_BYTES, read_certificate
from app.schemas import OcrResponse
from app.security import require_internal_token

FN = "read_certificate"
router = APIRouter(dependencies=[Depends(require_internal_token)])


@router.post("/v1/ocr/certificate", response_model=OcrResponse, operation_id="AI-07", name=FN)
async def certificate(file: Annotated[UploadFile, File()], services: ServicesDep) -> OcrResponse:
    data = await file.read(OCR_MAX_BYTES + 1)
    if len(data) > OCR_MAX_BYTES:
        raise payload_too_large()
    response = await cached_or_compute(
        services, FN, data, OcrResponse, lambda: read_certificate(data, services)
    )
    log_call(FN, response.meta)
    return response
