# MindCare Backend

2026 간지톤 19팀 — AI 기반 투트랙 정신건강 케어 플랫폼 백엔드

- 명세: MindCare TRD 백엔드 v0.4 (팀 디스코드 공유본)
- 스택: Spring Boot 4.1 · Java 21 · PostgreSQL 17 · Redis · Flyway · springdoc

## 로컬 실행

```bash
docker compose up -d        # PostgreSQL(5433) + Redis(6380)
./gradlew bootRun           # http://localhost:8090/v1
```

- 헬스체크: `GET http://localhost:8090/v1/health`
- API 문서: `http://localhost:8090/v1/docs` (OpenAPI: `/v1/openapi.json`)

## 테스트

```bash
docker compose up -d
./gradlew test
```

## 규칙

코드 규칙·패키지 구조·응답 형식은 [CLAUDE.md](CLAUDE.md) 참고 (사람이랑 AI가 같이 보는 규칙 파일).

> ⚠️ Public 레포입니다. `.env`, Firebase 서비스 계정 JSON, AWS 키는 절대 커밋하지 마세요.
> 개발 마감 2026-11-20(금) 23:59 이후 커밋 금지 (실격).
