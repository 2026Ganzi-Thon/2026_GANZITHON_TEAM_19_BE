# ai/Dockerfile — FastAPI AI 서버. 빌드 컨텍스트는 레포 루트 (TRD 18-4)
#   docker build -f ai/Dockerfile -t mindcare-ai .
# 레포 최상단 .dockerignore는 스프링 이미지용이라 ai/를 뺀다. 이 이미지는
# ai/Dockerfile.dockerignore를 대신 쓴다 (BuildKit이 Dockerfile 옆의 것을 우선한다).
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.9 /uv /usr/local/bin/uv
WORKDIR /app
COPY ai/pyproject.toml ai/uv.lock ./
RUN uv sync --frozen --no-dev
COPY ai/ .
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
RUN useradd -r -u 10002 ai && chown -R ai /app
USER ai
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1", "--no-access-log"]
