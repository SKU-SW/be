from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import settings
from .exceptions import ChzzkSessionException
from .models import ChzzkSessionConnectErrorRes
from .registry import chzzk_session_registry
from .router_chzzk import channel_router, session_router
from .services.broadcast_context_service import broadcast_context_service
from .services.chat_filter_service import chat_filter_service
from .services.chat_publish_service import chat_publish_service
from .services.gemini_validation_service import gemini_filter_service


def configure_logging() -> None:
    log_level_name = os.getenv("LOG_LEVEL", "INFO").upper()
    log_level = getattr(logging, log_level_name, logging.INFO)
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )


@asynccontextmanager
async def lifespan(_: FastAPI):
    configure_logging()
    logger = logging.getLogger(__name__)
    logger.info("Chat filter mode: %s", settings.chat_filter_mode)

    chzzk_session_registry.end_shutdown()
    await chat_publish_service.startup()
    await broadcast_context_service.startup()
    await gemini_filter_service.startup()
    await chat_filter_service.startup()
    yield
    chzzk_session_registry.begin_shutdown()
    await chat_filter_service.shutdown()
    await gemini_filter_service.shutdown()
    await broadcast_context_service.shutdown()
    await chzzk_session_registry.close_all_sessions()
    await chat_publish_service.shutdown()


app = FastAPI(
    title="SKU_SW FastAPI",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(session_router)
app.include_router(channel_router)


@app.get("/health", tags=["System"])
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.exception_handler(ChzzkSessionException)
async def handle_chzzk_session_exception(_: Request, exc: ChzzkSessionException) -> JSONResponse:
    error_response = ChzzkSessionConnectErrorRes(
        code=exc.code,
        message=exc.message,
        broadcastStreamId=exc.broadcast_stream_id,
        attemptId=exc.attempt_id,
    )
    return JSONResponse(status_code=exc.status_code, content=error_response.model_dump())


@app.exception_handler(RequestValidationError)
async def handle_validation_exception(_: Request, exc: RequestValidationError) -> JSONResponse:
    error_response = ChzzkSessionConnectErrorRes(
        code="INVALID_REQUEST",
        message="요청값이 올바르지 않습니다.",
        broadcastStreamId=None,
        attemptId=None,
    )
    return JSONResponse(status_code=400, content=error_response.model_dump())


@app.exception_handler(Exception)
async def handle_unexpected_exception(_: Request, exc: Exception) -> JSONResponse:
    logging.getLogger(__name__).exception("Unhandled FastAPI exception", exc_info=exc)
    error_response = ChzzkSessionConnectErrorRes(
        code="UNEXPECTED_INTERNAL_ERROR",
        message="서버 내부 오류가 발생했습니다.",
        broadcastStreamId=None,
        attemptId=None,
    )
    return JSONResponse(status_code=500, content=error_response.model_dump())
