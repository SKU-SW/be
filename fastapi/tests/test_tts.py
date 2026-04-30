"""
TTS FastAPI smoke tests

실행:
    pip install -r requirements.txt
    pytest fastapi/tests/test_tts.py -v

참고: supertonic 라이브러리가 설치되지 않은 환경에서도
      어댑터/라우터의 기본 구조는 mock 으로 검증한다.
"""

from __future__ import annotations

import io
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import TTSRequest
from app.tts_adapter import TTSAdapter

client = TestClient(app)

SAMPLE_PAYLOAD = {
    "broadcastStreamId": "test_stream_1234",
    "characterId": 7,
    "ttsId": "Microsoft Server Speech Text to Speech Voice (ko-KR, SunHiNeural)",
    "voiceText": "안녕하세요, 테스트입니다.",
    "broadcastDialogueId": 42,
}


def test_tts_request_model():
    req = TTSRequest(**SAMPLE_PAYLOAD)
    assert req.broadcastStreamId == "test_stream_1234"
    assert req.characterId == 7
    assert req.ttsId.startswith("Microsoft")
    assert req.voiceText == "안녕하세요, 테스트입니다."
    assert req.broadcastDialogueId == 42


def test_tts_request_missing_field():
    with pytest.raises(Exception):
        TTSRequest(characterId=1)  # type: ignore[call-arg]


def test_resolve_voice_style_from_legacy_id():
    adapter = TTSAdapter()
    assert adapter._resolve_voice_style_name("Microsoft ... SunHiNeural") == "F3"
    assert adapter._resolve_voice_style_name("Microsoft ... InJoonNeural") == "M3"
    assert adapter._resolve_voice_style_name("F1") == "F1"


@pytest.mark.asyncio
async def test_tts_adapter_missing_package():
    adapter = TTSAdapter()
    with patch("builtins.__import__", side_effect=ImportError("no supertonic")):
        with pytest.raises(RuntimeError, match="install supertonic"):
            await adapter.synthesize("test", "F3")


@pytest.mark.asyncio
async def test_tts_adapter_supertonic_mocked():
    adapter = TTSAdapter()

    fake_tts = MagicMock()
    fake_tts.sample_rate = 24000
    fake_tts.get_voice_style.return_value = {"voice": "F3"}
    fake_tts.synthesize.return_value = ([0.0, 0.1, -0.1, 0.0], [0.00016])

    fake_sf = MagicMock()

    def fake_write(buffer, data, sample_rate, format, subtype):
        assert sample_rate == 24000
        assert format == "WAV"
        assert subtype == "PCM_16"
        buffer.write(b"RIFF....WAVE")

    fake_sf.write.side_effect = fake_write

    with patch.object(adapter, "_init_engine") as mock_init, \
         patch.object(adapter, "_tts", fake_tts), \
         patch.dict("sys.modules", {"soundfile": fake_sf}):
        mock_init.return_value = None
        result = await adapter.synthesize("hello", "F3")

    assert result.startswith(b"RIFF")
    fake_tts.get_voice_style.assert_called_once_with(voice_name="F3")


def test_synthesize_endpoint_no_engine():
    with patch("app.router.tts_adapter.synthesize", side_effect=RuntimeError("TTS engine is not available")):
        response = client.post("/api/tts/synthesize", json=SAMPLE_PAYLOAD)
    assert response.status_code == 500
    assert "TTS engine" in response.text


def test_synthesize_endpoint_success():
    with patch("app.router.tts_adapter.synthesize", return_value=b"RIFF....WAVE"):
        response = client.post("/api/tts/synthesize", json=SAMPLE_PAYLOAD)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("multipart/form-data")
    assert b'name="metadata"' in response.content
    assert b'name="audio"' in response.content
    assert b"audio/wav" in response.content
    assert b"RIFF....WAVE" in response.content


def test_synthesize_endpoint_bad_body():
    response = client.post("/api/tts/synthesize", json={"bad": "data"})
    assert response.status_code == 422


def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "tts-fastapi"
