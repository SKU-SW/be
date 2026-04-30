"""
TTS 요청/응답 데이터 모델
"""

from typing import Optional

from pydantic import BaseModel, Field


class TTSRequest(BaseModel):
    """TTS 합성 요청 - Spring BroadcastGeminiService → FastAPI로 전달되는 payload"""
    broadcastStreamId: str = Field(
        ..., description="방송 스트림 ID (e.g. 'a1b2c3d4e5f6g7h8')"
    )
    characterId: int = Field(
        ..., description="캐릭터 ID (e.g. 1)"
    )
    ttsId: str = Field(
        ..., description="TTS 음성 ID 또는 Supertonic voice style (e.g. 'Microsoft ... SunHiNeural', 'F3', 'M3')"
    )
    voiceText: str = Field(
        ..., description="합성할 텍스트 (e.g. '안녕하세요, 시청자 여러분!')"
    )
    broadcastDialogueId: Optional[int] = Field(
        None, description="BroadcastDialogue PK (커서 용도, e.g. 42)"
    )
