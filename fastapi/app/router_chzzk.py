from __future__ import annotations

from fastapi import APIRouter

from .models import ChzzkChannelConnectReq, ChzzkChannelConnectRes, ChzzkSessionConnectErrorRes, ChzzkSessionConnectReq, ChzzkSessionConnectRes
from .services.chzzk_session_service import chzzk_session_service

session_router = APIRouter(prefix="/api/chzzk/session", tags=["CHZZK Session"])
channel_router = APIRouter(prefix="/api/chzzk/channel", tags=["CHZZK Channel"])


@session_router.post(
    "/connect",
    response_model=ChzzkSessionConnectRes,
    responses={
        400: {"model": ChzzkSessionConnectErrorRes},
        401: {"model": ChzzkSessionConnectErrorRes},
        503: {"model": ChzzkSessionConnectErrorRes},
        409: {"model": ChzzkSessionConnectErrorRes},
        500: {"model": ChzzkSessionConnectErrorRes},
        502: {"model": ChzzkSessionConnectErrorRes},
        504: {"model": ChzzkSessionConnectErrorRes},
    },
)
async def connect_chzzk_session(request: ChzzkSessionConnectReq) -> ChzzkSessionConnectRes:
    return await chzzk_session_service.connect_session(request)


@channel_router.post(
    "/connect",
    response_model=ChzzkChannelConnectRes,
    responses={400: {"model": ChzzkSessionConnectErrorRes}, 404: {"model": ChzzkSessionConnectErrorRes}, 500: {"model": ChzzkSessionConnectErrorRes}},
)
async def connect_chzzk_channel(request: ChzzkChannelConnectReq) -> ChzzkChannelConnectRes:
    return await chzzk_session_service.connect_channel(request)


@channel_router.post(
    "/disconnect",
    response_model=ChzzkChannelConnectRes,
    responses={400: {"model": ChzzkSessionConnectErrorRes}, 404: {"model": ChzzkSessionConnectErrorRes}, 500: {"model": ChzzkSessionConnectErrorRes}},
)
async def disconnect_chzzk_channel(request: ChzzkChannelConnectReq) -> ChzzkChannelConnectRes:
    return await chzzk_session_service.disconnect_channel(request)
