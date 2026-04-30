FROM python:3.11-slim

WORKDIR /app

# 시스템 의존성 (Supertonic/soundfile/HuggingFace 런타임)
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    git-lfs \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

RUN git lfs install

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/app/.cache/huggingface \
    XDG_CACHE_HOME=/app/.cache \
    SUPERTONIC_CACHE_DIR=/app/.cache/supertonic

# Python 의존성 설치
COPY fastapi/requirements.txt .
RUN python -m pip install --upgrade pip setuptools wheel \
    && python -m pip install -r requirements.txt

# 설치 검증 (빌드 단계에서 실패를 조기 확인)
RUN python -c "import supertonic; print('supertonic import ok:', supertonic.__file__)"

# 앱 코드 복사
COPY fastapi/app/ ./app/
COPY fastapi/tests/ ./tests/

RUN mkdir -p /app/.cache/huggingface /app/.cache/supertonic

# 포트
EXPOSE 8000

# 실행
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
