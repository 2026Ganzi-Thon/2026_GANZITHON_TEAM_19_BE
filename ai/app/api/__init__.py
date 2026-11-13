"""내부 API 라우터 (operation_id = AI-01 ~ AI-07). 엔드포인트마다 파일 하나."""

from fastapi import APIRouter

from app.api import masking, matching, ocr, referral_report, risk, session_report, tagging

router = APIRouter()
for _module in (tagging, matching, risk, session_report, referral_report, masking, ocr):
    router.include_router(_module.router)
