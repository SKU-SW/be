from __future__ import annotations

from fastapi.testclient import TestClient

from app.exceptions import ChzzkSessionException
from app.main import app
from app.models import ChzzkChannelConnectRes, ChzzkSessionConnectRes
from app.services.chzzk_session_service import chzzk_session_service


client = TestClient(app)


def test_connect_chzzk_session_success(monkeypatch) -> None:
    async def mock_connect_session(_request):
        return ChzzkSessionConnectRes(
            broadcastStreamId="stream-1",
            attemptId="attempt-1",
            sessionKey="session-key",
            channelId="channel-id",
        )

    monkeypatch.setattr(chzzk_session_service, "connect_session", mock_connect_session)

    response = client.post(
        "/api/chzzk/session/connect",
        json={
            "broadcastStreamId": "stream-1",
            "attemptId": "attempt-1",
            "accessToken": "token",
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "broadcastStreamId": "stream-1",
        "attemptId": "attempt-1",
        "sessionKey": "session-key",
        "channelId": "channel-id",
    }


def test_connect_chzzk_session_error_response(monkeypatch) -> None:
    async def mock_connect_session(_request):
        raise ChzzkSessionException(
            status_code=409,
            code="DUPLICATE_CONNECT_REQUEST",
            message="이미 동일한 방송 스트림에 대한 치지직 세션 연결이 진행 중이거나 완료되었습니다.",
            broadcast_stream_id="stream-1",
            attempt_id="attempt-1",
        )

    monkeypatch.setattr(chzzk_session_service, "connect_session", mock_connect_session)

    response = client.post(
        "/api/chzzk/session/connect",
        json={
            "broadcastStreamId": "stream-1",
            "attemptId": "attempt-1",
            "accessToken": "token",
        },
    )

    assert response.status_code == 409
    assert response.json() == {
        "code": "DUPLICATE_CONNECT_REQUEST",
        "message": "이미 동일한 방송 스트림에 대한 치지직 세션 연결이 진행 중이거나 완료되었습니다.",
        "broadcastStreamId": "stream-1",
        "attemptId": "attempt-1",
    }


def test_connect_chzzk_session_validation_error() -> None:
    response = client.post(
        "/api/chzzk/session/connect",
        json={
            "broadcastStreamId": "stream-1",
            "attemptId": "attempt-1",
        },
    )

    assert response.status_code == 400
    assert response.json() == {
        "code": "INVALID_REQUEST",
        "message": "요청값이 올바르지 않습니다.",
        "broadcastStreamId": None,
        "attemptId": None,
    }


def test_connect_chzzk_session_shutdown_error(monkeypatch) -> None:
    async def mock_connect_session(_request):
        raise ChzzkSessionException(
            status_code=503,
            code="SERVER_SHUTTING_DOWN",
            message="FastAPI 서버가 종료 중이어서 치지직 세션 연결을 처리할 수 없습니다.",
            broadcast_stream_id="stream-1",
            attempt_id="attempt-1",
        )

    monkeypatch.setattr(chzzk_session_service, "connect_session", mock_connect_session)

    response = client.post(
        "/api/chzzk/session/connect",
        json={
            "broadcastStreamId": "stream-1",
            "attemptId": "attempt-1",
            "accessToken": "token",
        },
    )

    assert response.status_code == 503
    assert response.json() == {
        "code": "SERVER_SHUTTING_DOWN",
        "message": "FastAPI 서버가 종료 중이어서 치지직 세션 연결을 처리할 수 없습니다.",
        "broadcastStreamId": "stream-1",
        "attemptId": "attempt-1",
    }


def test_connect_chzzk_channel_success(monkeypatch) -> None:
    async def mock_connect_channel(_request):
        return ChzzkChannelConnectRes(
            broadcastStreamId="stream-1",
            sessionKey="session-key",
            channelName="Chat:channel.message",
            status="연결 성공",
        )

    monkeypatch.setattr(chzzk_session_service, "connect_channel", mock_connect_channel)

    response = client.post(
        "/api/chzzk/channel/connect",
        json={
            "broadcastStreamId": "stream-1",
            "sessionKey": "session-key",
            "channelName": "Chat:channel.message",
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "broadcastStreamId": "stream-1",
        "sessionKey": "session-key",
        "channelName": "Chat:channel.message",
        "status": "연결 성공",
    }
