from __future__ import annotations

import asyncio
import logging
import re
import time
import uuid
from collections import deque
from dataclasses import dataclass, field

from ..config import settings
from ..registry import ActiveChzzkSession, chzzk_session_registry
from .broadcast_context_service import broadcast_context_service
from .chat_publish_service import chat_publish_service
from .gemini_validation_service import gemini_filter_service

logger = logging.getLogger(__name__)

URL_RE = re.compile(r"https?://|www\.", re.IGNORECASE)


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class BufferedChat:
    message_id: str
    broadcast_stream_id: str
    channel_id: str
    channel_name: str
    nickname: str
    user_role_code: str
    content: str
    normalized_content: str
    message_time: int | None = None
    received_at_monotonic: float = 0.0


@dataclass(slots=True)
class StreamFilterState:
    broadcast_stream_id: str
    channel_id: str
    channel_name: str
    buffer: deque[BufferedChat] = field(default_factory=deque)
    flush_task: asyncio.Task | None = None
    flush_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    overflow_drop_count: int = 0


@dataclass(slots=True)
class SentimentBufferState:
    broadcast_stream_id: str
    buffer: deque[str] = field(default_factory=deque)
    flush_task: asyncio.Task | None = None
    flush_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    publish_task: asyncio.Task | None = None


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------


class ChatFilterService:
    """Orchestrates: spam drop → 3s buffer → dedupe → Gemini batch selection → publish."""

    def __init__(self) -> None:
        self.stream_states: dict[str, StreamFilterState] = {}
        self.sentiment_states: dict[str, SentimentBufferState] = {}

    async def startup(self) -> None:
        logger.info(
            "ChatFilterService started | mode=%s window=%.1fs",
            settings.chat_filter_mode,
            settings.chat_filter_window_sec,
        )

    async def shutdown(self) -> None:
        for state in list(self.stream_states.values()):
            if state.flush_task is not None and not state.flush_task.done():
                state.flush_task.cancel()
        self.stream_states.clear()

        for state in list(self.sentiment_states.values()):
            if state.flush_task is not None and not state.flush_task.done():
                state.flush_task.cancel()
            if state.publish_task is not None and not state.publish_task.done():
                state.publish_task.cancel()
        self.sentiment_states.clear()

    # ----------------------------------------------------------------
    # Ingress
    # ----------------------------------------------------------------

    async def ingest_chat(self, session: ActiveChzzkSession, payload: dict) -> None:
        if settings.chat_filter_mode == "passthrough":
            message = _build_legacy_message(session.broadcast_stream_id, payload)
            if message is not None:
                await chat_publish_service.publish(session.channel_name or "", message)
            return

        content = payload.get("content", "")
        if not content or not content.strip():
            return

        # Sentiment buffer — receives ALL chats (no URL filter, no dedup)
        sentiment_state = self._get_or_create_sentiment_state(session.broadcast_stream_id)
        sentiment_state.buffer.append(content)
        if len(sentiment_state.buffer) > settings.sentiment_buffer_max:
            sentiment_state.buffer.popleft()
        if sentiment_state.flush_task is None or sentiment_state.flush_task.done():
            sentiment_state.flush_task = asyncio.create_task(
                self._sentiment_flush_after_window(session.broadcast_stream_id)
            )

        if URL_RE.search(content):
            return

        channel_id = payload.get("channelId")
        if not channel_id:
            return

        profile = payload.get("profile") or {}
        chat = BufferedChat(
            message_id=uuid.uuid4().hex[:12],
            broadcast_stream_id=session.broadcast_stream_id,
            channel_id=channel_id,
            channel_name=session.channel_name or "",
            nickname=profile.get("nickname", ""),
            user_role_code=profile.get("userRoleCode", ""),
            content=content,
            normalized_content=_normalize_for_dedupe(content),
            message_time=payload.get("messageTime"),
            received_at_monotonic=time.monotonic(),
        )

        state = self._get_or_create_state(chat)

        if _is_recent_duplicate(state.buffer, chat.normalized_content):
            return

        state.buffer.append(chat)
        if len(state.buffer) > settings.chat_filter_buffer_max:
            state.buffer.popleft()
            state.overflow_drop_count += 1

        if state.flush_task is None or state.flush_task.done():
            state.flush_task = asyncio.create_task(self._flush_after_window(chat.broadcast_stream_id))

    # ----------------------------------------------------------------
    # Stream lifecycle
    # ----------------------------------------------------------------

    def initialize_stream(self, session: ActiveChzzkSession) -> None:
        self.stream_states[session.broadcast_stream_id] = StreamFilterState(
            broadcast_stream_id=session.broadcast_stream_id,
            channel_id=session.channel_id,
            channel_name=session.channel_name or "",
        )

    def remove_stream(self, broadcast_stream_id: str) -> None:
        state = self.stream_states.pop(broadcast_stream_id, None)
        if state is not None and state.flush_task is not None and not state.flush_task.done():
            state.flush_task.cancel()

        sentiment_state = self.sentiment_states.pop(broadcast_stream_id, None)
        if sentiment_state is not None:
            if sentiment_state.flush_task is not None and not sentiment_state.flush_task.done():
                sentiment_state.flush_task.cancel()
            if sentiment_state.publish_task is not None and not sentiment_state.publish_task.done():
                sentiment_state.publish_task.cancel()

    # ----------------------------------------------------------------
    # Internal — buffer & flush
    # ----------------------------------------------------------------

    def _get_or_create_state(self, chat: BufferedChat) -> StreamFilterState:
        if chat.broadcast_stream_id not in self.stream_states:
            self.stream_states[chat.broadcast_stream_id] = StreamFilterState(
                broadcast_stream_id=chat.broadcast_stream_id,
                channel_id=chat.channel_id,
                channel_name=chat.channel_name,
            )
        return self.stream_states[chat.broadcast_stream_id]

    def _get_or_create_sentiment_state(self, broadcast_stream_id: str) -> SentimentBufferState:
        if broadcast_stream_id not in self.sentiment_states:
            self.sentiment_states[broadcast_stream_id] = SentimentBufferState(
                broadcast_stream_id=broadcast_stream_id,
            )
        return self.sentiment_states[broadcast_stream_id]

    async def _sentiment_flush_after_window(self, broadcast_stream_id: str) -> None:
        await asyncio.sleep(settings.sentiment_buffer_window_sec)

        state = self.sentiment_states.get(broadcast_stream_id)
        if state is None:
            return

        async with state.flush_lock:
            if not state.buffer:
                return
            chats = list(state.buffer)
            state.buffer.clear()

        if not chats:
            return

        # Call Gemini sentiment analysis
        result = await gemini_filter_service.analyze_sentiment(chats)

        if result is None:
            logger.debug("Sentiment analysis returned no result | streamId=%s", broadcast_stream_id)
            return

        # Accumulate to registry
        await chzzk_session_registry.accumulate_sentiment(
            broadcast_stream_id,
            result.positive_chat_count,
            result.neutral_chat_count,
            result.negative_chat_count,
        )
        logger.debug(
            "Sentiment accumulated | streamId=%s positive=%d neutral=%d negative=%d",
            broadcast_stream_id,
            result.positive_chat_count,
            result.neutral_chat_count,
            result.negative_chat_count,
        )

        # Start 60-second publish timer if not running
        if state.publish_task is None or state.publish_task.done():
            state.publish_task = asyncio.create_task(
                self._sentiment_publish_periodically(broadcast_stream_id)
            )

    async def _sentiment_publish_periodically(self, broadcast_stream_id: str) -> None:
        await asyncio.sleep(60.0)

        # Get accumulated sentiment and reset
        sentiment = await chzzk_session_registry.get_and_reset_sentiment(broadcast_stream_id)

        # Publish to Redis (even if empty)
        session = chzzk_session_registry.active_sessions.get(broadcast_stream_id)
        if session is None:
            return

        payload = {
            "channelId": session.channel_id,
            "broadcastStreamId": broadcast_stream_id,
            "positiveChatCount": sentiment.positive_chat_count,
            "neutralChatCount": sentiment.neutral_chat_count,
            "negativeChatCount": sentiment.negative_chat_count,
        }
        await chat_publish_service.publish(session.channel_name or "", payload)
        logger.debug(
            "Sentiment published | streamId=%s positive=%d neutral=%d negative=%d",
            broadcast_stream_id,
            sentiment.positive_chat_count,
            sentiment.neutral_chat_count,
            sentiment.negative_chat_count,
        )

    async def _flush_after_window(self, broadcast_stream_id: str) -> None:
        await asyncio.sleep(settings.chat_filter_window_sec)

        state = self.stream_states.get(broadcast_stream_id)
        if state is None:
            return

        async with state.flush_lock:
            if not state.buffer:
                return
            chats = list(state.buffer)
            state.buffer.clear()

        # dedupe
        seen: set[str] = set()
        unique: list[BufferedChat] = []
        for chat in reversed(chats):
            if chat.normalized_content not in seen:
                seen.add(chat.normalized_content)
                unique.append(chat)
        unique.reverse()

        candidates = unique[-settings.chat_filter_candidate_max:]
        if not candidates:
            return

        # Fetch live broadcast context
        context = await broadcast_context_service.get_context(broadcast_stream_id)
        if context is None:
            logger.warning("Broadcast context unavailable | streamId=%s", broadcast_stream_id)
            return

        # Build candidate dicts for Gemini prompt
        candidate_dicts = [
            {
                "messageId": c.message_id,
                "nickname": c.nickname,
                "content": c.content,
            }
            for c in candidates
        ]

        # Gemini batch selection
        result = await gemini_filter_service.select_chat(candidate_dicts, context)

        if result is None or result.selected_message_id is None:
            return

        # Find selected chat
        selected = next((c for c in candidates if c.message_id == result.selected_message_id), None)
        if selected is None:
            logger.warning("Gemini selected unknown messageId | id=%s", result.selected_message_id)
            return

        # Publish
        message = {
            "broadcastStreamId": selected.broadcast_stream_id,
            "channelId": selected.channel_id,
            "nickname": selected.nickname,
            "userRoleCode": selected.user_role_code,
            "content": selected.content,
            "messageTime": selected.message_time,
        }
        await chat_publish_service.publish(selected.channel_name, message)
        logger.debug(
            "Published filtered chat | streamId=%s selected=%s reason=%s",
            broadcast_stream_id,
            result.selected_message_id,
            result.reason,
        )


# ----------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------


def _build_legacy_message(broadcast_stream_id: str, payload: dict) -> dict | None:
    channel_id = payload.get("channelId")
    if not channel_id:
        return None
    profile = payload.get("profile") or {}
    return {
        "broadcastStreamId": broadcast_stream_id,
        "channelId": channel_id,
        "nickname": profile.get("nickname", ""),
        "userRoleCode": profile.get("userRoleCode", ""),
        "content": payload.get("content", ""),
        "messageTime": payload.get("messageTime"),
    }


def _normalize_for_dedupe(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"ㅋ{2,}", "ㅋㅋ", text)
    text = re.sub(r"ㅎ{2,}", "ㅎㅎ", text)
    text = re.sub(r"ㅠ{2,}", "ㅠㅠ", text)
    text = re.sub(r"ㅜ{2,}", "ㅜㅜ", text)
    text = re.sub(r"!{2,}", "!!", text)
    text = re.sub(r"\?{2,}", "??", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _is_recent_duplicate(buffer: deque[BufferedChat], normalized: str) -> bool:
    now = time.monotonic()
    for existing in reversed(buffer):
        if now - existing.received_at_monotonic > 10.0:
            break
        if existing.normalized_content == normalized:
            return True
    return False


chat_filter_service = ChatFilterService()