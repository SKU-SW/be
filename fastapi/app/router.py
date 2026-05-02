"""
TTS 합성 REST API 라우터

Spring BroadcastService → FastAPI HTTP 호출 → TTS 합성 → multipart 응답
"""

from __future__ import annotations

import json
import logging

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from .models import TTSRequest
from .tts_adapter import tts_adapter

logger = logging.getLogger(__name__)

# Spring application.yml 과 일치하는 경로 (문서 참조)
#   Spring applicaton.yml 의 spring.cloud.gateway 또는
#   Spring WebClient 호출 시 base URL: http://fastapi:8000
#   전체 경로: POST /api/tts/synthesize
router = APIRouter(prefix="/api/tts", tags=["TTS"])

_BOUNDARY = "----SKU_SW_TTS_Boundary_7d4a3f1c"


@router.post("/synthesize")
async def synthesize_tts(req: TTSRequest) -> Response:
    """TTS 음성 합성을 수행하고 multipart/binary 응답을 반환합니다.

    Request body (JSON):
      - broadcastStreamId  (str) - 방송 스트림 ID
      - characterId        (int) - 캐릭터 ID
      - ttsId              (str) - Edge TTS 음성 식별자
      - voiceText          (str) - 합성할 텍스트
      - broadcastDialogueCursorId(int) - BroadcastInfo Cursor ID

    Response (multipart/form-data):
      Part 1 - application/json : {characterId, voiceText, broadcastDialogueCursorId}
      Part 2 - audio/wav        : WAV 바이너리 데이터

    Spring 수신 예:
        WebClient 로 응답을 받은 뒤,
        BroadcastWebSocketHandler.sendVoiceWithMetadata(
            broadcastStreamId, voiceData, characterId, voiceText, broadcastDialogueCursorId
        ) 를 호출합니다.
    """
    logger.info(
        "TTS request received | streamId=%s charId=%s ttsId=%s text_len=%d",
        req.broadcastStreamId,
        req.characterId,
        req.ttsId,
        len(req.voiceText),
    )

    # 1. TTS 합성
    try:
        audio_bytes = await tts_adapter.synthesize(req.voiceText, req.ttsId)
    except RuntimeError as exc:
        logger.error("TTS engine unavailable: %s", exc)
        raise HTTPException(status_code=500, detail=str(exc))
    except Exception as exc:
        logger.exception("TTS synthesis failed for streamId=%s", req.broadcastStreamId)
        raise HTTPException(status_code=500, detail=f"TTS synthesis error: {exc}")

    if not audio_bytes:
        logger.warning("TTS returned empty audio | streamId=%s", req.broadcastStreamId)
        raise HTTPException(status_code=500, detail="TTS returned empty audio data")

    logger.info(
        "TTS success | streamId=%s audio_size=%d bytes",
        req.broadcastStreamId,
        len(audio_bytes),
    )

    # 2. multipart 응답 조립
    metadata = {
        "characterId": req.characterId,
        "voiceText": req.voiceText,
        "broadcastDialogueCursorId": req.broadcastDialogueCursorId,
    }
    metadata_json = json.dumps(metadata, ensure_ascii=False)

    # Part 1: metadata (JSON)
    part_1 = (
        f"--{_BOUNDARY}\r\n"
        f'Content-Type: application/json\r\n'
        f'Content-Disposition: form-data; name="metadata"\r\n\r\n'
        f"{metadata_json}\r\n"
    ).encode("utf-8")

    # Part 2: audio (binary)
    part_2_header = (
        f"--{_BOUNDARY}\r\n"
        f'Content-Type: audio/wav\r\n'
        f'Content-Disposition: form-data; name="audio"\r\n\r\n'
    ).encode("utf-8")

    part_2_footer = f"\r\n--{_BOUNDARY}--\r\n".encode("utf-8")

    body = b"".join([part_1, part_2_header, audio_bytes, part_2_footer])

    return Response(
        content=body,
        media_type=f'multipart/form-data; boundary={_BOUNDARY}',
        headers={
            "X-TTS-Stream-Id": req.broadcastStreamId,
            "X-TTS-Character-Id": str(req.characterId),
        },
    )
