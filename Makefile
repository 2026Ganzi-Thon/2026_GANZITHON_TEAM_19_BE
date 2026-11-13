# MindCare 백엔드 레포 공용 명령 (TRD 10-1).
# 지금은 AI 서버(ai/) 대상만 있다. 스프링 쪽 대상(openapi, test, migrate, seed,
# reset-demo, create-admin)은 담당자가 이 파일에 더한다.

.PHONY: ai-openapi ai-eval ai-demo-cache

# 내부 계약 ai/openapi.json 갱신 (TRD 16-11). 갱신한 파일을 같은 PR에 커밋한다.
ai-openapi:
	cd ai && uv run python -m app.export_openapi > openapi.json

# 프롬프트·모델 평가 (TRD 부록 B-4). 키가 주입된 서버에서 실 LLM으로 돈다.
ai-eval:
	cd ai && uv run python -m eval.run --suite all

# 시연 캐시 다시 만들기 (TRD 16-8). 만든 뒤 팀이 검수한다.
ai-demo-cache:
	cd ai && uv run python -m app.demo_cache.build
