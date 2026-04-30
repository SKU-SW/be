"""
edge-sunhi / edge-tts 어댑터

Microsoft Edge TTS 엔진에 대한 추상화 레이어.
edge-sunhi 라이브러리를 우선 시도하고, 없으면 edge-tts를 폴백합니다.
두 라이브러리 모두 설치되지 않은 경우 명확한 오류 메시지를 반환합니다.
"""

from __future__ import annotations

import logging
from typing import Optional

logger = logging.getLogger(__name__)


class TTSAdapter:
    """TTS 엔진 어댑터 - 싱글톤처럼 사용 (모듈 레벨 인스턴스)"""

    def __init__(self) -> None:
        self._engine: Optional[str] = None
        self._init_engine()

    # ------------------------------------------------------------------
    # Internal: 엔진 탐색
    # ------------------------------------------------------------------
    def _init_engine(self) -> None:
        for engine_name, import_path in [
            ("edge_sunhi", "edge_sunhi"),
            ("edge_tts", "edge_tts"),
        ]:
            try:
                __import__(import_path)
                self._engine = engine_name
                logger.info("TTS engine loaded: %s", engine_name)
                return
            except ImportError:
                logger.debug("TTS engine '%s' not available", engine_name)
                continue

        self._engine = None
        logger.warning(
            "No TTS engine installed. "
            "Install edge-tts:  pip install edge-tts"
        )

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    async def synthesize(self, text: str, voice: str) -> bytes:
        """지정된 텍스트와 음성으로 TTS 합성을 수행합니다.

        Args:
            text: 합성할 한글/영문 텍스트
            voice: Edge TTS 음성 식별자 (ttsId)

        Returns:
            MP3 형식의 오디오 바이너리 데이터

        Raises:
            RuntimeError: TTS 엔진을 찾을 수 없는 경우
        """
        if self._engine is None:
            raise RuntimeError(
                "TTS engine is not available. "
                "Please install edge-tts:  pip install edge-tts"
            )

        if self._engine == "edge_sunhi":
            return await self._synthesize_edge_sunhi(text, voice)
        return await self._synthesize_edge_tts(text, voice)

    # ------------------------------------------------------------------
    # engine-specific implementations
    # ------------------------------------------------------------------
    @staticmethod
    async def _synthesize_edge_tts(text: str, voice: str) -> bytes:
        """edge-tts (공식 오픈소스) 를 통한 합성"""
        import edge_tts  # type: ignore[import-untyped]

        communicate = edge_tts.Communicate(text, voice)
        audio_data = bytearray()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_data.extend(chunk["data"])
        return bytes(audio_data)

    @staticmethod
    async def _synthesize_edge_sunhi(text: str, voice: str) -> bytes:
        """edge-sunhi (커스텀/한글 래퍼) 를 통한 합성

        동일한 인터페이스(Communicate().stream())를 가정합니다.
        실제 API가 다를 경우 이 메서드만 교체하면 됩니다.
        """
        import edge_sunhi  # type: ignore[import-untyped]

        # edge-sunhi가 edge-tts와 동일한 API를 쓴다고 가정
        communicate = edge_sunhi.Communicate(text, voice)
        audio_data = bytearray()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_data.extend(chunk["data"])
        return bytes(audio_data)


# 모듈 전역 인스턴스 (앱 생명주기와 동일)
tts_adapter = TTSAdapter()
