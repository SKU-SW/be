"""
TTS FastAPI smoke tests

실행:
    pip install -r requirements.txt
    pytest docker/fastapi/tests/ -v

참고: edge-tts 라이브러리가 설치되지 않은 환경에서도
      TTSAdapter 및 라우터의 기본 구조는 검증 가능합니다.
"""

from __future__ import annotations

import sys
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import TTSRequest
from app.tts_adapter import TTSAdapter

client = TestClient(app)

# ---------------------------------------------------------------------------
# Unit: TTSRequest 모델
# ---------------------------------------------------------------------------
SAMPLE_PAYLOAD = {
    "broadcastStreamId": "test_stream_1234",
    "characterId": 7,
    "ttsId": "Microsoft Server Speech Text to Speech Voice (ko-KR, SunHiNeural)",
    "voiceText": "안녕하세요, 테스트입니다.",
    "broadcastDialogueId": 42,
}


def test_tts_request_model():
    """Pydantic 모델이 올바르게 로드되는지 확인"""
    req = TTSRequest(**SAMPLE_PAYLOAD)
    assert req.broadcastStreamId == "test_stream_1234"
    assert req.characterId == 7
    assert req.ttsId.startswith("Microsoft")
    assert req.voiceText == "안녕하세요, 테스트입니다."
    assert req.broadcastDialogueId == 42


def test_tts_request_missing_field():
    """필수 필드 누락 시 validation 에러 발생"""
    with pytest.raises(Exception):
        TTSRequest(characterId=1)  # type: ignore[call-arg]


# ---------------------------------------------------------------------------
# Unit: TTSAdapter
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_tts_adapter_no_engine():
    """엔진 미설치 시 RuntimeError 발생"""
    adapter = TTSAdapter()
    adapter._engine = None  # 엔진 없음 강제
    with pytest.raises(RuntimeError, match="TTS engine is not available"):
        await adapter.synthesize("test", "voice")


@pytest.mark.asyncio
async def test_tts_adapter_edge_tts_mocked():
    """edge-tts engine 모킹"""
    adapter = TTSAdapter()
    adapter._engine = "edge_tts"

    fake_chunks = [
        {"type": "audio", "data": b"\x00\x01\x02"},
        {"type": "audio", "data": b"\x03\x04\x05"},
        {"type": "end", "data": None},
    ]

    # Communicate.stream() 이 async generator 를 반환하도록 모킹
    class FakeCommunicate:
        def __init__(self, _text: str, _voice: str) -> None:
            pass

        async def stream(self):  # type: ignore[misc]
            for chunk in fake_chunks:
                yield chunk

    mock_edge_tts = MagicMock()
    mock_edge_tts.Communicate.side_effect = FakeCommunicate

    # edge_tts가 sys.modules 캐시에 없어야 local import가 mock 을 참조
    for key in list(sys.modules.keys()):
        if "edge_tts" in key:
            del sys.modules[key]

    with patch.dict("sys.modules", {"edge_tts": mock_edge_tts}):
        result = await adapter.synthesize("hello", "voice-id")

    assert result == b"\x00\x01\x02\x03\x04\x05"


# ---------------------------------------------------------------------------
# Integration: FastAPI 라우터 (엔진 없음 → 500)
# ---------------------------------------------------------------------------
def test_synthesize_endpoint_no_engine():
    """TTS 엔진 없이 호출 시 500 에러 반환"""
    response = client.post("/api/tts/synthesize", json=SAMPLE_PAYLOAD)
    assert response.status_code == 500
    assert "TTS engine" in response.text


def test_synthesize_endpoint_bad_body():
    """잘못된 요청 바디 → 422"""
    response = client.post("/api/tts/synthesize", json={"bad": "data"})
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# Integration: 헬스 체크
# ---------------------------------------------------------------------------
def test_health_check():
    """GET /health 가 200을 반환"""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "tts-fastapi"
