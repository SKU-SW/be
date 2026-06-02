from __future__ import annotations

from pydantic import BaseModel, Field


class ChzzkSessionConnectReq(BaseModel):
    broadcastStreamId: str = Field(..., min_length=1, description="방송 스트림 ID")
    attemptId: str = Field(..., min_length=1, description="방송 시작 시도 ID")
    accessToken: str = Field(..., min_length=1, description="치지직 Access Token")


class ChzzkSessionConnectRes(BaseModel):
    broadcastStreamId: str = Field(..., description="방송 스트림 ID")
    attemptId: str = Field(..., description="방송 시작 시도 ID")
    sessionKey: str = Field(..., description="치지직 세션 키")
    channelId: str = Field(..., description="치지직 채널 ID")


class ChzzkSessionConnectErrorRes(BaseModel):
    code: str = Field(..., description="에러 코드")
    message: str = Field(..., description="에러 메시지")
    broadcastStreamId: str | None = Field(default=None, description="방송 스트림 ID")
    attemptId: str | None = Field(default=None, description="방송 시작 시도 ID")


class ChzzkChannelConnectReq(BaseModel):
    broadcastStreamId: str = Field(..., min_length=1, description="방송 스트림 ID")
    sessionKey: str = Field(..., min_length=1, description="치지직 세션 키")
    channelName: str = Field(..., min_length=1, description="Redis 채널 이름")


class ChzzkChannelConnectRes(BaseModel):
    broadcastStreamId: str = Field(..., description="방송 스트림 ID")
    sessionKey: str = Field(..., description="치지직 세션 키")
    channelName: str = Field(..., description="Redis 채널 이름")
    status: str = Field(..., description="연결 상태")
