"""
FastAPI TTS Service - SKU_SW 프로젝트

Microsoft Edge TTS(edge-sunhi/edge-tts) 를 활용한 음성 합성 REST API 서버.
Spring Boot 백엔드에서 HTTP 호출하여 TTS 음성을 생성하고 multipart 응답을 받습니다.

실행 (로컬):
    pip install -r requirements.txt
    uvicorn app.main:app --reload --port 8000

도커 실행:
    docker compose up --build
"""

from __future__ import annotations

import logging

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .router import router as tts_router

# ---------------------------------------------------------------------------
# 로깅 설정
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

# ---------------------------------------------------------------------------
# FastAPI 앱 생성
# ---------------------------------------------------------------------------
app = FastAPI(
    title="SKU_SW TTS Service",
    description="Edge TTS 기반 음성 합성 서비스 (Spring 연동)",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

# ---------------------------------------------------------------------------
# CORS - Spring Nginx 프록시 환경 대응
# ---------------------------------------------------------------------------
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# 라우터 등록
# ---------------------------------------------------------------------------
app.include_router(tts_router)


# ---------------------------------------------------------------------------
# 헬스 체크
# ---------------------------------------------------------------------------
@app.get("/health", tags=["System"])
async def health_check():
    return {"status": "ok", "service": "tts-fastapi"}


# ---------------------------------------------------------------------------
# 직접 실행
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
