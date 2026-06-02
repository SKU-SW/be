from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import AsyncIterator

import socketio

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class PendingChzzkSessionConnect:
    broadcast_stream_id: str
    attempt_id: str
    access_token: str
    status: str = "REQUESTED"


@dataclass(slots=True)
class ActiveChzzkSession:
    broadcast_stream_id: str
    attempt_id: str
    session_key: str
    channel_id: str
    socket_client: socketio.AsyncClient
    channel_name: str | None = None
    redis_publish_ready: bool = False
    status: str = "SUBSCRIBED"
    connected_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


class ChzzkSessionRegistry:
    def __init__(self) -> None:
        self.pending_sessions: dict[str, PendingChzzkSessionConnect] = {}
        self.active_sessions: dict[str, ActiveChzzkSession] = {}
        self.inflight_sockets: dict[str, socketio.AsyncClient] = {}
        self.shutting_down = False
        self._stream_locks: dict[str, asyncio.Lock] = {}
        self._stream_locks_guard = asyncio.Lock()

    @asynccontextmanager
    async def stream_lock(self, broadcast_stream_id: str) -> AsyncIterator[None]:
        async with self._stream_locks_guard:
            lock = self._stream_locks.setdefault(broadcast_stream_id, asyncio.Lock())

        try:
            async with lock:
                yield
        finally:
            async with self._stream_locks_guard:
                current_lock = self._stream_locks.get(broadcast_stream_id)
                if current_lock is lock and not lock.locked() and not self.has_pending(broadcast_stream_id) and not self.has_active(broadcast_stream_id):
                    self._stream_locks.pop(broadcast_stream_id, None)

    def has_pending(self, broadcast_stream_id: str) -> bool:
        return broadcast_stream_id in self.pending_sessions

    def has_active(self, broadcast_stream_id: str) -> bool:
        return broadcast_stream_id in self.active_sessions

    def save_pending(self, pending: PendingChzzkSessionConnect) -> None:
        if self.shutting_down:
            raise RuntimeError("Registry is shutting down")
        self.pending_sessions[pending.broadcast_stream_id] = pending

    def get_pending(self, broadcast_stream_id: str) -> PendingChzzkSessionConnect | None:
        return self.pending_sessions.get(broadcast_stream_id)

    def update_pending_status(self, broadcast_stream_id: str, status: str) -> None:
        pending = self.pending_sessions.get(broadcast_stream_id)
        if pending is not None:
            pending.status = status

    def remove_pending(self, broadcast_stream_id: str) -> PendingChzzkSessionConnect | None:
        return self.pending_sessions.pop(broadcast_stream_id, None)

    def save_active(self, active: ActiveChzzkSession) -> None:
        if self.shutting_down:
            raise RuntimeError("Registry is shutting down")
        self.active_sessions[active.broadcast_stream_id] = active

    def remove_active(self, broadcast_stream_id: str) -> ActiveChzzkSession | None:
        return self.active_sessions.pop(broadcast_stream_id, None)

    def save_inflight_socket(self, broadcast_stream_id: str, socket_client: socketio.AsyncClient) -> None:
        if self.shutting_down:
            raise RuntimeError("Registry is shutting down")
        self.inflight_sockets[broadcast_stream_id] = socket_client

    def remove_inflight_socket(self, broadcast_stream_id: str) -> socketio.AsyncClient | None:
        return self.inflight_sockets.pop(broadcast_stream_id, None)

    def clear_pending(self) -> None:
        self.pending_sessions.clear()

    def begin_shutdown(self) -> None:
        self.shutting_down = True

    def end_shutdown(self) -> None:
        self.shutting_down = False

    async def close_all_sessions(self) -> None:
        active_sessions = list(self.active_sessions.values())
        inflight_sockets = list(self.inflight_sockets.values())
        self.active_sessions.clear()
        self.inflight_sockets.clear()
        self.pending_sessions.clear()

        for active_session in active_sessions:
            try:
                if active_session.socket_client.connected:
                    await active_session.socket_client.disconnect()
            except Exception as exc:
                logger.warning(
                    "Failed to disconnect active CHZZK session | streamId=%s attemptId=%s error=%s",
                    active_session.broadcast_stream_id,
                    active_session.attempt_id,
                    exc,
                )

        for socket_client in inflight_sockets:
            try:
                if socket_client.connected:
                    await socket_client.disconnect()
            except Exception as exc:
                logger.warning("Failed to disconnect inflight CHZZK socket | error=%s", exc)


chzzk_session_registry = ChzzkSessionRegistry()
