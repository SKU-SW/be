from __future__ import annotations

import asyncio
import json
import logging
import os
from dataclasses import dataclass, field

import httpx
import socketio

from ..exceptions import ChzzkSessionException
from ..models import ChzzkChannelConnectReq, ChzzkChannelConnectRes, ChzzkSessionConnectReq, ChzzkSessionConnectRes
from ..registry import ActiveChzzkSession, PendingChzzkSessionConnect, chzzk_session_registry
from .chat_publish_service import chat_publish_service

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class SocketWaitState:
    socket_client: socketio.AsyncClient
    connected_event: asyncio.Event = field(default_factory=asyncio.Event)
    subscribed_event: asyncio.Event = field(default_factory=asyncio.Event)
    session_key: str | None = None
    channel_id: str | None = None


class ChzzkSessionService:
    def __init__(self) -> None:
        self.chzzk_api_base_url = os.getenv("CHZZK_API_BASE_URL", "https://openapi.chzzk.naver.com")
        self.session_auth_path = os.getenv("CHZZK_SESSION_AUTH_PATH", "/open/v1/sessions/auth")
        self.chat_subscribe_path = os.getenv("CHZZK_CHAT_SUBSCRIBE_PATH", "/open/v1/sessions/events/subscribe/chat")
        self.http_timeout_seconds = float(os.getenv("CHZZK_HTTP_TIMEOUT_SEC", "5"))
        self.socket_connect_timeout_seconds = float(os.getenv("CHZZK_SOCKET_CONNECT_TIMEOUT_SEC", "5"))
        self.system_event_timeout_seconds = float(os.getenv("CHZZK_SYSTEM_EVENT_TIMEOUT_SEC", "5"))
        self.socket_request_timeout_seconds = float(os.getenv("CHZZK_SOCKET_REQUEST_TIMEOUT_SEC", "5"))

    async def connect_session(self, request: ChzzkSessionConnectReq) -> ChzzkSessionConnectRes:
        if chzzk_session_registry.shutting_down:
            raise ChzzkSessionException(status_code=503, code="SERVER_SHUTTING_DOWN", message="FastAPI 서버가 종료 중이어서 치지직 세션 연결을 처리할 수 없습니다.", broadcast_stream_id=request.broadcastStreamId, attempt_id=request.attemptId)

        async with chzzk_session_registry.stream_lock(request.broadcastStreamId):
            if chzzk_session_registry.has_pending(request.broadcastStreamId) or chzzk_session_registry.has_active(request.broadcastStreamId):
                raise ChzzkSessionException(status_code=409, code="DUPLICATE_CONNECT_REQUEST", message="이미 동일한 방송 스트림에 대한 치지직 세션 연결이 진행 중이거나 완료되었습니다.", broadcast_stream_id=request.broadcastStreamId, attempt_id=request.attemptId)
            pending = PendingChzzkSessionConnect(request.broadcastStreamId, request.attemptId, request.accessToken)
            chzzk_session_registry.save_pending(pending)

        socket_client = None
        try:
            session_url = await self.request_session_auth_url(pending)
            chzzk_session_registry.update_pending_status(request.broadcastStreamId, "AUTH_URL_RECEIVED")
            socket_state = await self.open_socket_connection(session_url, pending)
            socket_client = socket_state.socket_client
            chzzk_session_registry.update_pending_status(request.broadcastStreamId, "CONNECTED")
            chzzk_session_registry.update_pending_status(request.broadcastStreamId, "SUBSCRIBING")
            await self.subscribe_chat_event(pending, socket_state.session_key)
            channel_id = await self.wait_subscribed_event(pending, socket_state)

            active_session = ActiveChzzkSession(
                broadcast_stream_id=request.broadcastStreamId,
                attempt_id=request.attemptId,
                session_key=socket_state.session_key or "",
                channel_id=channel_id,
                socket_client=socket_client,
                channel_name=None,
                redis_publish_ready=False,
            )

            async with chzzk_session_registry.stream_lock(request.broadcastStreamId):
                if socket_client is None or not socket_client.connected:
                    raise ChzzkSessionException(status_code=502, code="CHZZK_SOCKET_DISCONNECTED", message="치지직 세션 연결이 완료 응답 직전에 종료되었습니다.", broadcast_stream_id=request.broadcastStreamId, attempt_id=request.attemptId)
                chzzk_session_registry.save_active(active_session)
                chzzk_session_registry.remove_inflight_socket(request.broadcastStreamId)
                chzzk_session_registry.remove_pending(request.broadcastStreamId)

            return ChzzkSessionConnectRes(broadcastStreamId=request.broadcastStreamId, attemptId=request.attemptId, sessionKey=active_session.session_key, channelId=active_session.channel_id)
        except ChzzkSessionException:
            await self.cleanup_failed_connection(request.broadcastStreamId, request.attemptId, socket_client)
            raise
        except Exception as exc:
            logger.exception("[ChzzkSessionService] connect_session() - Unexpected error | streamId=%s attemptId=%s", request.broadcastStreamId, request.attemptId)
            await self.cleanup_failed_connection(request.broadcastStreamId, request.attemptId, socket_client)
            raise ChzzkSessionException(status_code=500, code="UNEXPECTED_INTERNAL_ERROR", message="치지직 세션 연결 처리 중 예기치 않은 내부 오류가 발생했습니다.", broadcast_stream_id=request.broadcastStreamId, attempt_id=request.attemptId) from exc

    async def connect_channel(self, request: ChzzkChannelConnectReq) -> ChzzkChannelConnectRes:
        async with chzzk_session_registry.stream_lock(request.broadcastStreamId):
            active_session = chzzk_session_registry.active_sessions.get(request.broadcastStreamId)
            if active_session is None:
                raise ChzzkSessionException(status_code=404, code="CHZZK_ACTIVE_SESSION_NOT_FOUND", message="활성 치지직 세션을 찾을 수 없습니다.", broadcast_stream_id=request.broadcastStreamId)
            if active_session.session_key != request.sessionKey:
                raise ChzzkSessionException(status_code=400, code="CHZZK_SESSION_KEY_MISMATCH", message="치지직 sessionKey가 일치하지 않습니다.", broadcast_stream_id=request.broadcastStreamId)
            active_session.channel_name = request.channelName
            active_session.redis_publish_ready = True

        return ChzzkChannelConnectRes(broadcastStreamId=request.broadcastStreamId, sessionKey=request.sessionKey, channelName=request.channelName, status="연결 성공")

    async def disconnect_channel(self, request: ChzzkChannelConnectReq) -> ChzzkChannelConnectRes:
        async with chzzk_session_registry.stream_lock(request.broadcastStreamId):
            active_session = chzzk_session_registry.active_sessions.get(request.broadcastStreamId)
            if active_session is None:
                raise ChzzkSessionException(status_code=404, code="CHZZK_ACTIVE_SESSION_NOT_FOUND", message="활성 치지직 세션을 찾을 수 없습니다.", broadcast_stream_id=request.broadcastStreamId)
            if active_session.session_key != request.sessionKey:
                raise ChzzkSessionException(status_code=400, code="CHZZK_SESSION_KEY_MISMATCH", message="치지직 sessionKey가 일치하지 않습니다.", broadcast_stream_id=request.broadcastStreamId)
            active_session.channel_name = None
            active_session.redis_publish_ready = False

        disconnect_target = active_session.socket_client
        if disconnect_target.connected:
            await disconnect_target.disconnect()

        return ChzzkChannelConnectRes(broadcastStreamId=request.broadcastStreamId, sessionKey=request.sessionKey, channelName=request.channelName, status="연결 종료")

    async def request_session_auth_url(self, pending: PendingChzzkSessionConnect) -> str:
        async with httpx.AsyncClient(timeout=self.http_timeout_seconds) as client:
            response = await client.get(f"{self.chzzk_api_base_url}{self.session_auth_path}", headers=self._build_headers(pending.access_token))
        if response.status_code in (401, 403):
            raise ChzzkSessionException(status_code=401, code="CHZZK_SESSION_AUTH_UNAUTHORIZED", message="치지직 Access Token이 유효하지 않습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        if response.is_error:
            raise ChzzkSessionException(status_code=502, code="CHZZK_SESSION_AUTH_FAILED", message="치지직 세션 인증 API 호출에 실패했습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        payload = response.json()
        session_url = self._extract_value(payload, "url")
        if not session_url:
            raise ChzzkSessionException(status_code=502, code="CHZZK_SESSION_AUTH_RESPONSE_INVALID", message="치지직 세션 인증 API 응답에 연결 URL이 없습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        return session_url

    async def open_socket_connection(self, session_url: str, pending: PendingChzzkSessionConnect) -> SocketWaitState:
        chzzk_session_registry.update_pending_status(pending.broadcast_stream_id, "SOCKET_CONNECTING")
        socket_client = socketio.AsyncClient(reconnection=False, request_timeout=self.socket_request_timeout_seconds)
        chzzk_session_registry.save_inflight_socket(pending.broadcast_stream_id, socket_client)
        state = SocketWaitState(socket_client=socket_client)

        @socket_client.event
        async def disconnect() -> None:
            async with chzzk_session_registry.stream_lock(pending.broadcast_stream_id):
                active_session = chzzk_session_registry.active_sessions.get(pending.broadcast_stream_id)
                if active_session is not None and active_session.attempt_id == pending.attempt_id:
                    active_session.redis_publish_ready = False
                    active_session.channel_name = None
                    chzzk_session_registry.remove_active(pending.broadcast_stream_id)
                chzzk_session_registry.remove_inflight_socket(pending.broadcast_stream_id)

        @socket_client.on("SYSTEM")
        async def on_system(payload: object) -> None:
            normalized_payload = self._normalize_payload(payload)
            message_type = normalized_payload.get("type")
            data = normalized_payload.get("data") or {}
            if message_type == "connected":
                state.session_key = self._extract_value(data, "sessionKey")
                state.connected_event.set()
                return
            if message_type == "subscribed" and self._extract_value(data, "eventType") == "CHAT":
                state.channel_id = self._extract_value(data, "channelId")
                state.subscribed_event.set()
                return
            await self._publish_chat_event_if_ready(pending.broadcast_stream_id, message_type or "UNKNOWN", normalized_payload)

        connect_task = socket_client.connect(session_url, transports=["websocket"])

        await asyncio.wait_for(connect_task, timeout=self.socket_connect_timeout_seconds)
        await asyncio.wait_for(state.connected_event.wait(), timeout=self.system_event_timeout_seconds)
        if not state.session_key:
            raise ChzzkSessionException(status_code=502, code="CHZZK_SESSION_KEY_MISSING", message="치지직 connected 시스템 메시지에 sessionKey가 없습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        return state

    async def subscribe_chat_event(self, pending: PendingChzzkSessionConnect, session_key: str | None) -> None:
        if not session_key:
            raise ChzzkSessionException(status_code=502, code="CHZZK_SESSION_KEY_MISSING", message="치지직 채팅 이벤트 구독에 필요한 sessionKey가 없습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        async with httpx.AsyncClient(timeout=self.http_timeout_seconds) as client:
            response = await client.post(f"{self.chzzk_api_base_url}{self.chat_subscribe_path}", headers=self._build_headers(pending.access_token), params={"sessionKey": session_key})
        if response.status_code in (401, 403):
            raise ChzzkSessionException(status_code=401, code="CHZZK_CHAT_SUBSCRIBE_UNAUTHORIZED", message="치지직 채팅 이벤트 구독 권한이 없습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        if response.is_error:
            raise ChzzkSessionException(status_code=502, code="CHZZK_CHAT_SUBSCRIBE_FAILED", message="치지직 채팅 이벤트 구독 API 호출에 실패했습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)

    async def wait_subscribed_event(self, pending: PendingChzzkSessionConnect, state: SocketWaitState) -> str:
        await asyncio.wait_for(state.subscribed_event.wait(), timeout=self.system_event_timeout_seconds)
        if not state.channel_id:
            raise ChzzkSessionException(status_code=502, code="CHZZK_CHANNEL_ID_MISSING", message="치지직 subscribed 시스템 메시지에 channelId가 없습니다.", broadcast_stream_id=pending.broadcast_stream_id, attempt_id=pending.attempt_id)
        chzzk_session_registry.update_pending_status(pending.broadcast_stream_id, "SUBSCRIBED")
        return state.channel_id

    async def cleanup_failed_connection(self, broadcast_stream_id: str, attempt_id: str, socket_client: socketio.AsyncClient | None) -> None:
        async with chzzk_session_registry.stream_lock(broadcast_stream_id):
            active_session = chzzk_session_registry.remove_active(broadcast_stream_id)
            chzzk_session_registry.remove_pending(broadcast_stream_id)
            inflight_socket = chzzk_session_registry.remove_inflight_socket(broadcast_stream_id)
        disconnect_target = socket_client or inflight_socket or (active_session.socket_client if active_session is not None else None)
        if disconnect_target is not None and disconnect_target.connected:
            await disconnect_target.disconnect()

    async def _publish_chat_event_if_ready(self, broadcast_stream_id: str, event_type: str, payload: dict) -> None:
        active_session = chzzk_session_registry.active_sessions.get(broadcast_stream_id)
        if active_session is None or not active_session.redis_publish_ready or not active_session.channel_name:
            return
        await chat_publish_service.publish(active_session.channel_name, {
            "type": event_type,
            "broadcastStreamId": broadcast_stream_id,
            "channelId": active_session.channel_id,
            "channelName": active_session.channel_name,
            "payload": payload,
        })

    def _build_headers(self, access_token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"}

    def _normalize_payload(self, payload: object) -> dict:
        if isinstance(payload, str):
            try:
                parsed_payload = json.loads(payload)
            except json.JSONDecodeError:
                return {}
            return parsed_payload if isinstance(parsed_payload, dict) else {}
        return payload if isinstance(payload, dict) else {}

    def _extract_value(self, payload: dict | None, key: str) -> str | None:
        if not isinstance(payload, dict):
            return None
        direct_value = payload.get(key)
        if isinstance(direct_value, str) and direct_value.strip():
            return direct_value
        content_value = payload.get("content")
        if isinstance(content_value, dict):
            nested_value = content_value.get(key)
            if isinstance(nested_value, str) and nested_value.strip():
                return nested_value
        data_value = payload.get("data")
        if isinstance(data_value, dict):
            nested_value = data_value.get(key)
            if isinstance(nested_value, str) and nested_value.strip():
                return nested_value
        return None


chzzk_session_service = ChzzkSessionService()
