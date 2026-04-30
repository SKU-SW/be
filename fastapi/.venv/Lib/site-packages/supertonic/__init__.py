"""Supertonic — Lightning Fast, On-Device TTS.

Supertonic is a high-performance, on-device text-to-speech system powered by
ONNX Runtime. It delivers state-of-the-art speech synthesis with unprecedented
speed and efficiency.

Supertonic-2 supports multilingual synthesis with 5 languages:
- English (en)
- Korean (ko)
- Spanish (es)
- Portuguese (pt)
- French (fr)

Example:
    ```python
    from supertonic import TTS

    tts = TTS()
    style = tts.get_voice_style("M1")

    # English (default)
    wav, duration = tts.synthesize("Welcome to Supertonic!", voice_style=style, lang="en")

    # Korean
    wav_ko, _ = tts.synthesize("안녕하세요!", voice_style=style, lang="ko")

    tts.save_audio(wav, "output.wav")
    ```
"""

from __future__ import annotations

import logging

from .config import AVAILABLE_LANGUAGES, AVAILABLE_MODELS, DEFAULT_LANGUAGE, DEFAULT_MODEL
from .core import Style, UnicodeProcessor
from .pipeline import TTS

__version__ = "1.1.2"

__all__ = [
    "TTS",
    "Style",
    "UnicodeProcessor",
    "AVAILABLE_LANGUAGES",
    "AVAILABLE_MODELS",
    "DEFAULT_LANGUAGE",
    "DEFAULT_MODEL",
    "__version__",
]

# Configure logging
logging.getLogger(__name__).addHandler(logging.NullHandler())
