"""환경 설정 (TRD 7-3). 필수 값이 없거나 형식이 틀리면 기동에 실패한다.

값은 환경변수로만 받는다. 서버(EC2)가 ai.env를 Compose의 env_file로 넣어 준다.
앱이 파일을 직접 읽지 않으므로 API 키를 레포나 로컬 파일에 둘 일이 없다.
"""

from functools import lru_cache
from typing import Literal, Self

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

MIN_INTERNAL_TOKEN_LENGTH = 32

# 부록 H-03: 공급자는 Upstage(Solar)로 결정. staging·CI·로컬 기본은 fake.
LlmProvider = Literal["upstage", "fake"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    ai_internal_tokens: str = ""
    llm_provider: LlmProvider = "fake"
    llm_api_key: SecretStr | None = None
    llm_model: str | None = None
    llm_daily_token_cap: int = 2_000_000
    demo_mode: bool = False
    redis_url: str | None = None
    fake_llm_latency_ms: int = 0

    @property
    def internal_tokens(self) -> tuple[str, ...]:
        return tuple(t.strip() for t in self.ai_internal_tokens.split(",") if t.strip())

    @model_validator(mode="after")
    def _validate(self) -> Self:
        tokens = self.internal_tokens
        if not tokens:
            raise ValueError("AI_INTERNAL_TOKENS is required")
        if any(len(t) < MIN_INTERNAL_TOKEN_LENGTH for t in tokens):
            raise ValueError(
                f"each AI_INTERNAL_TOKENS entry needs {MIN_INTERNAL_TOKEN_LENGTH}+ characters"
            )
        if self.llm_daily_token_cap <= 0:
            raise ValueError("LLM_DAILY_TOKEN_CAP must be positive")
        if self.llm_provider == "upstage":
            # 모델은 AI 담당이 평가 결과로 골라 지정한다 (TRD 16-10). 기본값은 없다.
            if self.llm_api_key is None:
                raise ValueError("LLM_API_KEY is required when LLM_PROVIDER=upstage")
            if not self.llm_model:
                raise ValueError("LLM_MODEL is required when LLM_PROVIDER=upstage")
            # Redis가 없으면 일일 토큰 상한(TRD 16-9)이 프로세스 메모리에만 남아
            # 재시작할 때마다 0으로 돌아간다. 실 공급자를 쓸 때는 반드시 있어야 한다.
            if not self.redis_url:
                raise ValueError("REDIS_URL is required when LLM_PROVIDER=upstage")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
