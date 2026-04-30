FROM python:3.11-slim

WORKDIR /app

# 시스템 의존성 (Edge TTS가 필요한 경우 최소)
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Python 의존성 설치
COPY fastapi/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 앱 코드 복사
COPY fastapi/app/ ./app/

# 포트
EXPOSE 8000

# 실행
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
