# MindCare Backend — 작업 규칙

사람이랑 AI(Codex, Claude Code 등)가 모두 이 규칙을 따른다. Codex는 이 파일을, Claude Code는 `CLAUDE.md`를 통해 이 파일을 읽는다. 규칙을 바꾸려면 PR로 이 파일을 먼저 고친다.

## 명세 기준
- **기능 스펙은 TRD 백엔드 v0.5.1이 기준이다.** DB 스키마(11장), API 명세(13장), 실시간(14장), 비즈니스 규칙(15장)은 TRD 그대로 따른다.
- **코드 구조는 이 파일이 우선이다.** TRD의 폴더·클래스·코드 예시와 이 파일이 다르면 이 파일대로 짠다 (아래 "TRD와 다른 코드 구조").
- 기능 스펙을 TRD와 다르게 구현해야 하면 PR 설명에 적고 기획(김준성)에게 알린다 (TRD: 코드가 먼저 바뀌면 24시간 안에 문서를 맞춤).
- 간지톤 범위: P0(Phase 1·2)만 먼저. P1(Phase 3)은 P0가 끝난 뒤.

## 스택
- Spring Boot 4.1, Java 21, Gradle
- PostgreSQL 17 + Flyway (스키마는 Flyway만 바꾼다. `ddl-auto: validate`)
- Spring Data JPA, Redis, springdoc-openapi
- 인증: Firebase ID 토큰 검증 (자체 JWT·비밀번호 없음)
- 실시간 채팅: STOMP over WebSocket (TRD D-07의 Socket.IO 대신, 프론트는 `@stomp/stompjs`)

## TRD와 다른 코드 구조
백엔드 회의에서 정한 구조를 유지한다 (11/5 합의). 프론트에서 보이는 동작은 TRD와 같다.

| 항목 | TRD v0.5.1 | 이 레포 |
| --- | --- | --- |
| Java | 25 | 21 |
| 빌드 | Gradle Kotlin DSL, `server/` 폴더 | Gradle Groovy, 레포 최상단 |
| 패키지 | `kr.mindcare`, `common/` | `com.mindcare`, `global/` |
| 인증 | Spring Security 필터, `CurrentUser` | `AuthInterceptor`, `@LoginUser AuthUser` |
| 권한 | `@RequireRole` | service에서 `user.requireRole(...)` |
| 토큰 폐기 | `tokens_revoked_at`로 서버에서 판단 | `verifyIdToken(token, true)`로 Firebase에 확인 (`tokens_revoked_at` 컬럼은 있지만 아직 안 씀) |
| 마이그레이션 | `V<yyyyMMddHHmm>__` | `V{번호}__` |

AI 서버(FastAPI)는 TRD대로 이 레포의 `ai/` 폴더에 둔다 (담당: 김준성). 스프링 코드 위치는 그대로라 `ai/`만 추가된다.

## 패키지 구조

```
com.mindcare
├─ global/                 # 공통 (BE-1 관리)
│  ├─ config/              # CORS, OpenAPI 등
│  ├─ error/               # ErrorCode, AppException, GlobalExceptionHandler
│  ├─ response/            # PageResponse
│  └─ web/                 # RequestIdFilter 등
└─ domain/
   ├─ (BE-1) auth, user, credential, slot, payment, settlement, notification, admin, health
   └─ (BE-2) intake, match, session, message, report, consent, referral, supervision, complaint, risk, kpi
      └─ 각 도메인: controller / service / repository / entity / dto
```

- controller는 요청 검증·응답 변환만. 비즈니스 규칙·권한 판단은 service. DB 접근은 repository.

## 도메인 간 규칙
- 다른 담당자의 도메인 패키지는 직접 고치지 않는다. 엔티티도 각자 자기 도메인 것만 만든다.
- **상대 도메인의 테이블은 직접 바꾸지 않는다.** 상대 repository를 쓰지 말고 그 도메인의 **service 메서드**를 호출한다. 메서드가 없으면 담당자에게 요청하고, 이름과 형태는 필요할 때 맞춘다.
  - 예: 결제 완료 → 세션 확정, 예약 → 슬롯 상태 변경, 슈퍼비전 제출 → 정산 해제
- **다른 도메인의 사용자는 `User` 엔티티 대신 `UUID`로 저장한다** (예: `private UUID clientId;`). 사용자 정보가 필요하면 user 도메인의 service로 조회한다.

## 인증 (TRD 6-1, 12-1)
- 공개 경로를 뺀 모든 API는 `AuthInterceptor`가 Firebase 토큰을 확인한다. 공개 경로는 `/public/**`, `/health`, `/certificates/verify/**`, API 문서뿐이다 (`WebConfig`).
- 로그인 사용자는 컨트롤러 파라미터 `@LoginUser AuthUser user`로 받는다. 가입 전이면 404 `USER_NOT_REGISTERED`가 자동으로 나간다.
- 역할·계정 상태 확인은 service에서 `user.requireRole(UserRole.CLIENT)`를 호출한다. 역할이 다르면 `FORBIDDEN_ROLE`, active가 아니면 상태별 오류가 나간다.
- 정지·심사 중 계정도 허용하는 API(예: `GET /me`)는 `requireRole`을 부르지 않는다.
- 해당 세션의 당사자인지 같은 자원 권한은 각 도메인 service에서 따로 확인한다 (`FORBIDDEN_RESOURCE`).
- 테스트에서는 `@MockitoBean TokenVerifier`로 토큰 검증을 대신한다 (`MeControllerTest` 참고).
- Firebase 키 경로는 환경변수 `FIREBASE_CREDENTIALS_PATH`. local은 없어도 서버가 뜨지만 모든 토큰이 거절된다.

## API 규칙 (TRD 12장)
- 모든 경로는 `/v1` 아래 (`server.servlet.context-path`로 붙음 → 컨트롤러에는 `/v1` 빼고 적는다).
- 경로는 kebab-case 복수형 (`/me/device-tokens`, `/sessions/{id}`).
- JSON 필드는 **snake_case** (Jackson 전역 설정. 자바 필드는 camelCase로 쓰면 자동 변환).
- 단건 응답은 DTO를 **감싸지 않고** 그대로 반환. 목록은 `PageResponse` (`items`, `next_cursor`).
- 오류는 무조건 `throw new AppException(ErrorCode.XXX)` → `{ "error": { code, message, details, request_id } }`.
  - 새 오류 코드가 필요하면 `ErrorCode`에 추가하고 PR에 적는다.
- 시간은 `Instant`(UTC)로 다루고 ISO 8601로 응답. KST 변환은 프론트에서.
- 모든 PK는 UUID (TRD: UUIDv7).
- 엔드포인트마다 `@Operation(operationId = "TRD API ID")`를 단다. 예: `@Operation(operationId = "USR-01")`.

## DB 마이그레이션 (Flyway)
- 위치: `src/main/resources/db/migration/`
- TRD 11장 테이블 30개(업무 28 + `jobs`, `shedlock`)는 `V2`, `V3`에 전부 들어 있다. 새로 만들지 말고 엔티티만 맞춰서 만든다.
- 파일명: `V{번호}__{설명}.sql` (예: `V4__add_session_note.sql`). 추가할 때는 develop의 최신 번호를 확인하고, 겹치면 나중에 머지하는 쪽이 번호를 올린다.
- 이미 머지된 마이그레이션 파일은 절대 수정하지 않는다. 고칠 게 있으면 새 파일을 만든다.
- DDL은 TRD 11장 그대로. 상태값은 enum 타입 대신 `text + check`.
- `_enc` 컬럼(민감정보)은 애플리케이션에서 AES-256-GCM으로 암호화해서 `bytea`로 저장한다.

## Git
- `develop`이 기본 작업 브랜치다. 작업 브랜치는 `develop`에서 따고 PR도 `develop`으로 보낸다.
- `main`은 배포할 코드만 받는다 (`develop` → `main` 릴리스 PR). `develop`·`main` 직접 push 금지.
- 머지: 각자 도메인 PR은 CI 통과 후 본인이 머지한다. `global/`, 마이그레이션, 이 파일, 상대 도메인에 영향 주는 변경은 상대 확인 후 머지한다.
- 브랜치명: `feat/<영역>-<내용>`, `fix/<영역>-<내용>`, `chore/<내용>`, `docs/<내용>` (예: `feat/auth-firebase`)
- 커밋: Conventional Commits (`feat(auth): Firebase 토큰 검증`, `fix(slot): 겹침 검사`)
- 커밋은 작게, PR은 기능 단위로.

## 보안 (Public 레포)
- `.env`, Firebase 서비스 계정 JSON, AWS 키, 비밀번호는 **절대 커밋 금지**. 설정은 환경변수로만.
- 요청 본문·채팅 메시지·리포트 내용은 로그에 남기지 않는다.
- 민감 자원(리포트·대화록·동의·인테이크) API는 권한 없는 역할·타인 자원일 때 403 테스트를 같이 작성한다.

## 마감
- 개발 마감 **2026-11-20(금) 23:59**. 이후 커밋은 실격이므로 자동 커밋(봇·Actions)은 마감 전에 끈다.
