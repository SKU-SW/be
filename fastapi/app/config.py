from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    chat_filter_mode: str  # "filtered" | "passthrough"
    chat_filter_window_sec: float
    chat_filter_buffer_max: int
    chat_filter_candidate_max: int
    chat_filter_recent_dialogue_limit: int
    chat_filter_tail_scan_limit: int
    broadcast_redis_host: str
    broadcast_redis_port: int
    broadcast_redis_password: str | None
    gemini_api_key: str | None
    gemini_model: str
    gemini_api_base_url: str
    gemini_timeout_sec: float
    gemini_connect_timeout_sec: float
    sentiment_buffer_window_sec: float
    sentiment_buffer_max: int

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            chat_filter_mode=os.getenv("CHAT_FILTER_MODE", "filtered"),
            chat_filter_window_sec=float(os.getenv("CHAT_FILTER_WINDOW_SEC", "3.0")),
            chat_filter_buffer_max=int(os.getenv("CHAT_FILTER_BUFFER_MAX", "200")),
            chat_filter_candidate_max=int(os.getenv("CHAT_FILTER_CANDIDATE_MAX", "20")),
            chat_filter_recent_dialogue_limit=int(os.getenv("CHAT_FILTER_RECENT_DIALOGUE_LIMIT", "50")),
            chat_filter_tail_scan_limit=int(os.getenv("CHAT_FILTER_TAIL_SCAN_LIMIT", "100")),
            broadcast_redis_host=os.getenv("DEV_BROADCAST_REDIS_HOST", "localhost"),
            broadcast_redis_port=int(os.getenv("DEV_BROADCAST_REDIS_PORT", "6380")),
            broadcast_redis_password=os.getenv("DEV_BROADCAST_REDIS_PW") or None,
            gemini_api_key=os.getenv("GEMINI_API_KEY") or None,
            gemini_model=os.getenv("GEMINI_MODEL", "gemini-3.1-flash-lite-preview"),
            gemini_api_base_url=os.getenv(
                "GEMINI_API_BASE_URL",
                "https://generativelanguage.googleapis.com/v1beta",
            ),
            gemini_timeout_sec=float(os.getenv("GEMINI_TIMEOUT_SEC", "10.0")),
            gemini_connect_timeout_sec=float(os.getenv("GEMINI_CONNECT_TIMEOUT_SEC", "0.5")),
            sentiment_buffer_window_sec=float(os.getenv("SENTIMENT_BUFFER_WINDOW_SEC", "20.0")),
            sentiment_buffer_max=int(os.getenv("SENTIMENT_BUFFER_MAX", "500")),
        )


settings = Settings.from_env()