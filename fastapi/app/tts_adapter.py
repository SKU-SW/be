"""
Supertonic-2 어댑터

Spring -> FastAPI -> Supertonic-2 로컬 TTS 합성 흐름을 담당한다.
기존 API 계약은 유지하고, FastAPI 내부에서만 TTS 엔진을 교체한다.
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import re
from typing import Any, Optional

logger = logging.getLogger(__name__)

_SUPPORTED_VOICES = {f"M{i}" for i in range(1, 6)} | {f"F{i}" for i in range(1, 6)}
_SUPPORTED_LANGS = {"en", "ko", "es", "pt", "fr"}


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


class TTSAdapter:
    """Supertonic-2 TTS 엔진 어댑터 - 모듈 레벨 인스턴스로 사용"""

    def __init__(self) -> None:
        self._engine: Optional[str] = None
        self._tts: Optional[Any] = None
        self._voice_styles: dict[str, Any] = {}
        self._default_lang = os.getenv("SUPERTONIC_LANG", "ko").strip().lower() or "ko"
        self._default_female_voice = os.getenv("SUPERTONIC_DEFAULT_FEMALE", "F3").strip().upper() or "F3"
        self._default_male_voice = os.getenv("SUPERTONIC_DEFAULT_MALE", "M3").strip().upper() or "M3"
        self._default_total_steps = int(os.getenv("SUPERTONIC_TOTAL_STEPS", "5"))
        self._default_speed = float(os.getenv("SUPERTONIC_SPEED", "1.05"))
        self._auto_download = _env_bool("SUPERTONIC_AUTO_DOWNLOAD", True)

    def _init_engine(self) -> None:
        if self._tts is not None:
            return

        try:
            from supertonic import TTS  # type: ignore[import-untyped]
        except ImportError as exc:
            logger.exception("Supertonic package is not installed")
            raise RuntimeError(
                "TTS engine is not available. Please install supertonic: pip install supertonic"
            ) from exc

        try:
            self._tts = TTS(auto_download=self._auto_download)
            self._engine = "supertonic-2"
            available_voices = list(getattr(self._tts, "voice_style_names", []))
            logger.info(
                "TTS engine loaded: %s | sample_rate=%s | voices=%s",
                self._engine,
                getattr(self._tts, "sample_rate", "unknown"),
                available_voices,
            )
        except Exception as exc:
            logger.exception("Failed to initialize Supertonic-2")
            raise RuntimeError(f"Failed to initialize Supertonic-2: {exc}") from exc

    async def synthesize(self, text: str, voice: str) -> bytes:
        """지정된 텍스트와 음성으로 TTS 합성을 수행한다.

        Args:
            text: 합성할 텍스트
            voice: 기존 ttsId 또는 Supertonic voice style (M1~M5, F1~F5)

        Returns:
            WAV 형식의 오디오 바이너리 데이터
        """
        if not text or not text.strip():
            raise ValueError("voiceText must not be empty")

        return await asyncio.to_thread(self._synthesize_sync, text, voice)

    def _synthesize_sync(self, text: str, voice: str) -> bytes:
        self._init_engine()
        if self._tts is None:
            raise RuntimeError("Supertonic-2 is not initialized")

        try:
            import numpy as np  # type: ignore[import-untyped]
            import soundfile as sf  # type: ignore[import-untyped]
        except ImportError as exc:
            raise RuntimeError(
                "Supertonic runtime dependencies are missing. Please install supertonic and soundfile."
            ) from exc

        lang = self._resolve_lang(voice)
        voice_style_name = self._resolve_voice_style_name(voice)
        voice_style = self._get_voice_style(voice_style_name)

        wav, duration = self._tts.synthesize(
            text,
            voice_style=voice_style,
            lang=lang,
            total_steps=self._default_total_steps,
            speed=self._default_speed,
        )

        wav_array = np.asarray(wav).squeeze()
        if wav_array.size == 0:
            raise RuntimeError("Supertonic-2 returned empty audio data")

        duration_array = np.asarray(duration).reshape(-1)
        duration_seconds = float(duration_array[0]) if duration_array.size else 0.0
        sample_rate = int(getattr(self._tts, "sample_rate", 24000))
        sample_count = min(len(wav_array), max(1, int(sample_rate * duration_seconds)))
        trimmed_wav = wav_array[:sample_count]

        buffer = io.BytesIO()
        sf.write(buffer, trimmed_wav, sample_rate, format="WAV", subtype="PCM_16")
        return buffer.getvalue()

    def _get_voice_style(self, voice_style_name: str) -> Any:
        if voice_style_name not in self._voice_styles:
            if self._tts is None:
                self._init_engine()
            if self._tts is None:
                raise RuntimeError("Supertonic-2 is not initialized")
            available_voices = set(getattr(self._tts, "voice_style_names", []))
            if available_voices and voice_style_name not in available_voices:
                raise RuntimeError(
                    f"Invalid voice '{voice_style_name}'. Available voices: {sorted(available_voices)}"
                )
            self._voice_styles[voice_style_name] = self._tts.get_voice_style(voice_name=voice_style_name)
        return self._voice_styles[voice_style_name]

    def _resolve_lang(self, voice: str) -> str:
        if not voice:
            return self._default_lang

        match = re.search(r"\b([a-z]{2})-[A-Z]{2}\b", voice)
        if match:
            lang = match.group(1).lower()
            if lang in _SUPPORTED_LANGS:
                return lang

        return self._default_lang if self._default_lang in _SUPPORTED_LANGS else "ko"

    def _resolve_voice_style_name(self, voice: str) -> str:
        normalized = (voice or "").strip().upper()
        if normalized in _SUPPORTED_VOICES:
            return normalized

        voice_lower = (voice or "").strip().lower()
        male_tokens = ("injoon", "hyunsu", "male", "man", "boy")
        female_tokens = ("sunhi", "sun-hi", "jihye", "seoyeon", "female", "woman", "girl")

        if any(token in voice_lower for token in male_tokens):
            return self._default_male_voice if self._default_male_voice in _SUPPORTED_VOICES else "M3"

        if any(token in voice_lower for token in female_tokens):
            return self._default_female_voice if self._default_female_voice in _SUPPORTED_VOICES else "F3"

        return self._default_female_voice if self._default_female_voice in _SUPPORTED_VOICES else "F3"


tts_adapter = TTSAdapter()
