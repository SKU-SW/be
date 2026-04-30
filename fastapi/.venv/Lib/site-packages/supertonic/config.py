"""Configuration and constants for Supertonic TTS package.

This module centralizes all configuration values, magic numbers, and default
settings used throughout the package.
"""

import logging
import os
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

# Available models
AVAILABLE_MODELS = ["supertonic", "supertonic-2"]
DEFAULT_MODEL = "supertonic-2"

# Model configuration mapping
MODEL_CONFIGS = {
    "supertonic": {
        "repo": "Supertone/supertonic",
        "cache_dir": "supertonic",
        "multilingual": False,
    },
    "supertonic-2": {
        "repo": "Supertone/supertonic-2",
        "cache_dir": "supertonic2",
        "multilingual": True,
    },
}

# Default model settings (can be overridden by environment variables)
DEFAULT_MODEL_REPO = os.getenv("SUPERTONIC_MODEL_REPO", MODEL_CONFIGS[DEFAULT_MODEL]["repo"])
DEFAULT_CACHE_DIR = os.getenv(
    "SUPERTONIC_CACHE_DIR", str(Path.home() / ".cache" / MODEL_CONFIGS[DEFAULT_MODEL]["cache_dir"])
)
DEFAULT_MODEL_REVISION = os.getenv("SUPERTONIC_MODEL_REVISION", "main")


def get_model_config(model_name: str) -> dict:
    """Get configuration for a specific model.

    Args:
        model_name: Model name ("supertonic" or "supertonic-2")

    Returns:
        Dictionary with model configuration (repo, cache_dir, multilingual)

    Raises:
        ValueError: If model_name is not valid
    """
    if model_name not in MODEL_CONFIGS:
        raise ValueError(
            f"Invalid model: '{model_name}'. " f"Available models: {', '.join(AVAILABLE_MODELS)}"
        )
    return MODEL_CONFIGS[model_name]


def get_model_cache_dir(model_name: str) -> Path:
    """Get cache directory for a specific model.

    Args:
        model_name: Model name ("supertonic" or "supertonic-2")

    Returns:
        Path to the model's cache directory
    """
    config = get_model_config(model_name)
    return Path.home() / ".cache" / config["cache_dir"]


def get_model_repo(model_name: str) -> str:
    """Get HuggingFace repo ID for a specific model.

    Args:
        model_name: Model name ("supertonic" or "supertonic-2")

    Returns:
        HuggingFace repository ID
    """
    config = get_model_config(model_name)
    return config["repo"]


def is_multilingual_model(model_name: str) -> bool:
    """Check if a model supports multilingual synthesis.

    Args:
        model_name: Model name ("supertonic" or "supertonic-2")

    Returns:
        True if model supports multiple languages
    """
    config = get_model_config(model_name)
    return config["multilingual"]


# Model paths
ONNX_DIR = Path("onnx")
VOICE_STYLES_DIR = Path("voice_styles")

CFG_REL_PATH = ONNX_DIR / "tts.json"
UNICODE_INDEXER_REL_PATH = ONNX_DIR / "unicode_indexer.json"
DP_ONNX_REL_PATH = ONNX_DIR / "duration_predictor.onnx"
TEXT_ENC_ONNX_REL_PATH = ONNX_DIR / "text_encoder.onnx"
VECTOR_EST_ONNX_REL_PATH = ONNX_DIR / "vector_estimator.onnx"
VOCODER_ONNX_REL_PATH = ONNX_DIR / "vocoder.onnx"

# Language configuration (supertonic-2 multilingual support)
AVAILABLE_LANGUAGES = ["en", "ko", "es", "pt", "fr"]
DEFAULT_LANGUAGE = "en"

# TTS parameters - defaults
DEFAULT_TOTAL_STEPS = 5
DEFAULT_SPEED = 1.05
DEFAULT_MAX_CHUNK_LENGTH = 300
DEFAULT_MAX_CHUNK_LENGTH_KO = 120  # Korean requires shorter chunks
DEFAULT_SILENCE_DURATION = 0.3  # seconds

# TTS parameters - constraints
MIN_SPEED = 0.7
MAX_SPEED = 2.0
MIN_TOTAL_STEPS = 1
MAX_TOTAL_STEPS = 100

# ONNX Runtime configuration
# TODO: Add parsing of SUPERTONIC_ONNX_PROVIDERS environment variable
DEFAULT_ONNX_PROVIDERS = ["CPUExecutionProvider"]  # GPU support can be added by extending this list


def _parse_env_int(env_var: str, default: Optional[int] = None) -> Optional[int]:
    """Parse integer from environment variable with validation.

    Args:
        env_var: Environment variable name
        default: Default value if not set or invalid

    Returns:
        Parsed integer or default value
    """
    value = os.getenv(env_var)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        logger.warning(f"Invalid value for {env_var}: '{value}'. Using default: {default}")
        return default


# Thread configuration - None means let ONNX Runtime decide automatically
DEFAULT_INTRA_OP_NUM_THREADS = _parse_env_int("SUPERTONIC_INTRA_OP_THREADS")
DEFAULT_INTER_OP_NUM_THREADS = _parse_env_int("SUPERTONIC_INTER_OP_THREADS")

# Text processing
MAX_TEXT_LENGTH = 100_000  # Maximum characters per single synthesis call

# Logging configuration
LOG_FORMAT = "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
LOG_LEVEL = os.getenv("SUPERTONIC_LOG_LEVEL", "INFO")
