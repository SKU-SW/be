from __future__ import annotations

import json
import logging
import os

from redis.asyncio import Redis

logger = logging.getLogger(__name__)


class ChatPublishService:
    def __init__(self) -> None:
        self.redis: Redis | None = None

    async def startup(self) -> None:
        self.redis = Redis(
            host=os.getenv("DEV_CHAT_REDIS_HOST") or "localhost",
            port=int(os.getenv("DEV_CHAT_REDIS_PORT") or "6381"),
            password=os.getenv("DEV_CHAT_REDIS_PW") or "1234",
            decode_responses=True,
        )

    async def shutdown(self) -> None:
        if self.redis is not None:
            await self.redis.aclose()
            self.redis = None

    async def publish(self, channel_name: str, payload: dict) -> None:
        if self.redis is None:
            logger.warning("Chat Redis publisher is not initialized | channel=%s", channel_name)
            return
        await self.redis.publish(channel_name, json.dumps(payload, ensure_ascii=False))


chat_publish_service = ChatPublishService()
