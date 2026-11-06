-- TRD 백엔드 v0.5.1 11장에서 추가된 부분: 토큰 폐기 시각, 인프라 테이블 2개

alter table users add column tokens_revoked_at timestamptz;   -- 토큰 폐기 시각: auth_time이 이보다 이르면 401 (공통 6-1)

create table jobs (               -- 비동기 작업 큐 겸 트랜잭셔널 아웃박스 (15-11)
  id uuid primary key,
  type text not null,             -- generate_session_report | generate_referral_report | mask_transcript
                                  -- | issue_certificate | send_push | send_reminder
  dedup_key text not null,        -- 멱등 키 (예: session_report:{session_id})
  payload jsonb not null default '{}',    -- 대상 ID만. 민감정보 금지
  status text not null default 'queued' check (status in ('queued','running','succeeded','failed','cancelled')),
  attempts smallint not null default 0,
  max_attempts smallint not null default 3,
  run_at timestamptz not null default now(),   -- 지연 실행(리마인드)·재시도 시각
  locked_by text,
  locked_until timestamptz,
  last_error text,
  finished_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index jobs_dedup_active on jobs (dedup_key) where status in ('queued','running');
create index jobs_ready on jobs (run_at) where status = 'queued';

create table shedlock (           -- 주기 배치 중복 실행 방지 (15-8, ShedLock 표준 스키마라 created_at 없음)
  name varchar(64) primary key,
  lock_until timestamp not null,
  locked_at timestamp not null,
  locked_by varchar(255) not null
);
