from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field

from redis.asyncio import Redis

from ..config import settings

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class BroadcastDialogue:
    cursor_id: int | None
    subject: str
    content: str
    emotion: str | None = None
    created_at: str | None = None
    data_status: str | None = None


@dataclass(slots=True)
class BroadcastContext:
    """Live broadcast context fetched from Redis for Gemini prompt construction."""
    broadcast_stream_id: str
    character_raw: dict | None = None
    summary_text: str | None = None
    recent_dialogues: list[BroadcastDialogue] = field(default_factory=list)
    fetched_at_monotonic: float = 0.0


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------


class BroadcastContextService:
    """Read-only access to Broadcast Redis for live context data."""

    def __init__(self) -> None:
        self.redis: Redis | None = None

    async def startup(self) -> None:
        kwargs: dict = {"decode_responses": True}
        if settings.broadcast_redis_password:
            kwargs["password"] = settings.broadcast_redis_password
        self.redis = Redis(host=settings.broadcast_redis_host, port=settings.broadcast_redis_port, **kwargs)
        await self.redis.ping()
        logger.info("Broadcast Redis connected | %s:%d", settings.broadcast_redis_host, settings.broadcast_redis_port)

    async def shutdown(self) -> None:
        if self.redis is not None:
            await self.redis.aclose()
            self.redis = None

    async def get_context(self, broadcast_stream_id: str) -> BroadcastContext | None:
        if self.redis is None:
            return None
        try:
            character_raw = await self._read_character_raw(broadcast_stream_id)
            summary_text, dialogues = await self._read_recent_dialogues(broadcast_stream_id)
            return BroadcastContext(
                broadcast_stream_id=broadcast_stream_id,
                character_raw=character_raw,
                summary_text=summary_text,
                recent_dialogues=dialogues,
                fetched_at_monotonic=time.monotonic(),
            )
        except Exception:
            logger.exception("get_context failed | streamId=%s", broadcast_stream_id)
            return None

    # ----------------------------------------------------------------
    # Internal helpers
    # ----------------------------------------------------------------

    async def _read_character_raw(self, broadcast_stream_id: str) -> dict | None:
        raw = await self._read_key(f"BroadcastCharacter:{broadcast_stream_id}")
        if raw is None:
            return None
        return json.loads(raw)

    async def _read_recent_dialogues(self, broadcast_stream_id: str) -> tuple[str | None, list[BroadcastDialogue]]:
        tail_scan = settings.chat_filter_tail_scan_limit
        raw_list = await self._read_list(f"BroadcastInfo:{broadcast_stream_id}", start=-tail_scan, stop=-1)

        summary_text: str | None = None
        active_dialogues: list[BroadcastDialogue] = []

        empty_summary = "(오늘 방송 요약 없음)"
        for raw in raw_list:
            if raw is None:
                continue
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if data.get("dataStatus") != "ACTIVE":
                continue
            subject = data.get("subject", "")
            content = data.get("content", "")
            if subject == "SYSTEM_SUMMARY":
                if content and content != empty_summary:
                    summary_text = content
                continue
            if not content:
                continue
            active_dialogues.append(BroadcastDialogue(
                cursor_id=data.get("cursorId"),
                subject=subject,
                content=content,
                emotion=data.get("emotion"),
                created_at=data.get("createdAt"),
                data_status=data.get("dataStatus"),
            ))

        limit = settings.chat_filter_recent_dialogue_limit
        dialogues = active_dialogues[-limit:] if len(active_dialogues) > limit else active_dialogues
        return summary_text, dialogues

    async def _read_key(self, key: str) -> str | None:
        assert self.redis is not None
        return await self.redis.get(key)

    async def _read_list(self, key: str, start: int, stop: int) -> list[str | None]:
        assert self.redis is not None
        return await self.redis.lrange(key, start, stop)


broadcast_context_service = BroadcastContextService()