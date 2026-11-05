-- TRD 백엔드 v0.4 11장 DDL (테이블 28개). 11장 표기대로 모든 테이블에 created_at을 넣었다.
-- 상태값은 PG enum 대신 text + CHECK. _enc는 AES-256-GCM 암호문(bytea), _hmac은 검색용 해시.
-- btree_gist 확장은 V1에서 생성.

-- =====================================================================
-- 11-1. 회원
-- =====================================================================

create table users (
  id uuid primary key,
  firebase_uid text not null unique,
  role text not null check (role in ('client','trainee','expert','admin')),
  nickname text not null unique,                 -- 내담자 닉네임 / 공급자는 내부용
  status text not null default 'active'
    check (status in ('active','pending_review','rejected','suspended','banned','restricted','withdrawn')),
  suspended_until timestamptz,
  restricted_until timestamptz,
  ethics_training_required boolean not null default false,
  training_data_opt_in boolean not null default false,   -- 수련 목적 비식별 활용 동의(내담자)
  marketing_opt_in boolean not null default false,
  withdrawn_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table identity_verifications (            -- 결제용 본인인증, 결제 모듈만 복호화
  user_id uuid primary key references users(id) on delete cascade,
  name_enc bytea not null,
  birth_enc bytea not null,
  phone_enc bytea not null,
  phone_hmac bytea not null unique,
  provider text not null,                         -- mock | pass ...
  verified_at timestamptz not null,
  created_at timestamptz not null default now()
);

create table provider_profiles (
  user_id uuid primary key references users(id) on delete cascade,
  track text not null check (track in ('trainee','expert')),
  display_name text not null unique,              -- 활동명(공개)
  real_name_enc bytea not null,
  service_label text check (service_label in ('coaching','listener','buddy')),  -- 수련생만
  specialties text[] not null default '{}',       -- 태그 코드, 최대 5
  bio text not null default '',
  career jsonb not null default '[]',             -- 전문가 경력 [{org, from, to}]
  school_enc bytea,                               -- 수련생 소속
  accepts_supervision boolean not null default true,
  rating_avg numeric(3,2),
  rating_count int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on provider_profiles using gin (specialties);

create table credentials (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('enrollment','completion','national','society')),
  cert_name text not null,                        -- 예: 임상심리사 1급
  cert_number_enc bytea,
  issued_on date,
  file_key text,                                  -- S3 key
  ocr_result jsonb,
  user_corrected boolean not null default false,
  verify_method text check (verify_method in ('api','ocr','manual')),
  verify_result text not null default 'none' check (verify_result in ('match','mismatch','unavailable','none')),
  verify_raw jsonb,
  status text not null default 'draft' check (status in ('draft','submitted','approved','rejected')),
  reject_reason text,
  reviewed_by uuid references users(id),
  reviewed_at timestamptz,
  created_at timestamptz not null default now()
);

create table availability_slots (
  id uuid primary key,
  provider_id uuid not null references users(id) on delete cascade,
  start_at timestamptz not null,
  end_at timestamptz not null,
  status text not null default 'open' check (status in ('open','held','booked')),
  hold_expires_at timestamptz,
  session_id uuid,
  created_at timestamptz not null default now(),
  exclude using gist (provider_id with =, tstzrange(start_at, end_at) with &&)   -- 겹침 금지
);
create index on availability_slots (provider_id, start_at) where status = 'open';

create table device_tokens (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  token text not null unique,
  platform text not null default 'web',
  last_seen_at timestamptz,
  created_at timestamptz not null default now()
);

-- =====================================================================
-- 11-2. 접수·매칭·상담
-- =====================================================================

create table intakes (
  id uuid primary key,
  client_id uuid not null references users(id),
  category text not null check (category in ('career','burnout','relationship','romance','family','self','other')),
  text_enc bytea not null,
  share_raw boolean not null default false,       -- 상담사에게 원문 공개
  preferred_times text[] not null default '{}',   -- morning|afternoon|evening|night
  ai_tags jsonb not null default '{}',            -- {emotions[], distortions[], keywords[]}
  ai_status text not null default 'ok' check (ai_status in ('ok','fallback')),
  risk_level text not null default 'none' check (risk_level in ('none','low','medium','high','critical')),
  notify_when_available boolean not null default false,
  created_at timestamptz not null default now()
);

create table matches (
  id uuid primary key,
  intake_id uuid not null references intakes(id) on delete cascade,
  provider_id uuid not null references users(id),
  track text not null check (track in ('coaching','professional')),
  score numeric(5,4) not null,
  reasons jsonb not null,
  rank smallint not null,
  created_at timestamptz not null default now(),
  unique (intake_id, provider_id)
);

create table sessions (
  id uuid primary key,
  track text not null check (track in ('coaching','professional')),
  client_id uuid not null references users(id),
  provider_id uuid not null references users(id),
  intake_id uuid references intakes(id),          -- 재예약이면 이전 세션의 인테이크를 이어받음
  referral_id uuid,                               -- 전문 세션일 때 (같은 연계의 이후 회기도 같은 값)
  rebook_of uuid references sessions(id),         -- "같은 상담사와 다시" 예약일 때 이전 세션
  slot_id uuid not null references availability_slots(id),
  scheduled_start timestamptz not null,
  scheduled_end timestamptz not null,
  status text not null check (status in ('pending_payment','confirmed','in_progress','completed','cancelled','no_show')),
  price int not null,
  client_joined_at timestamptz,
  provider_joined_at timestamptz,
  started_at timestamptz,
  ended_at timestamptz,
  ended_by text check (ended_by in ('provider','client','system')),
  cancel_reason text,
  cancelled_by uuid,
  provider_note_enc bytea,                        -- 상담 메모(비공개)
  rating smallint check (rating between 1 and 5),
  review_enc bytea,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on sessions (client_id, scheduled_start desc);
create index on sessions (provider_id, scheduled_start desc);
create index on sessions (status, scheduled_start);
-- 11-5: 연계에 포함할 수 있는 회기 조회용 부분 인덱스
create index on sessions (client_id, scheduled_start desc) where track = 'coaching' and status = 'completed';

create table messages (
  id uuid primary key,
  session_id uuid not null references sessions(id) on delete cascade,
  sender_id uuid references users(id),            -- null = 시스템 메시지
  kind text not null default 'text' check (kind in ('text','system','crisis_resources')),
  body_enc bytea not null,
  client_msg_id text,
  risk_level text not null default 'none',
  created_at timestamptz not null default now(),
  unique (session_id, client_msg_id)
);
create index on messages (session_id, id);

create table mood_checks (
  id uuid primary key,
  session_id uuid not null references sessions(id) on delete cascade,
  client_id uuid not null references users(id),
  phase text not null check (phase in ('before','after')),
  score smallint not null check (score between 1 and 10),
  created_at timestamptz not null default now(),
  unique (session_id, phase)
);

create table risk_events (
  id uuid primary key,
  source text not null check (source in ('intake','message','contact_exchange','report')),
  intake_id uuid references intakes(id),
  session_id uuid references sessions(id),
  message_id uuid,
  client_id uuid not null references users(id),
  provider_id uuid references users(id),
  level text not null check (level in ('low','medium','high','critical')),
  rule_hits jsonb not null default '[]',
  llm_score numeric(4,3),
  evidence_enc bytea not null,
  status text not null default 'open' check (status in ('open','acknowledged','escalated','resolved')),
  acked_by uuid,
  acked_at timestamptz,
  escalated_to uuid,
  escalated_at timestamptz,
  resolved_by uuid,
  resolved_at timestamptz,
  resolution_note_enc bytea,
  false_positive boolean,
  created_at timestamptz not null default now()
);
create index on risk_events (status, level, created_at);

-- =====================================================================
-- 11-3. 연계·수련
-- =====================================================================

create table reports (
  id uuid primary key,
  scope text not null check (scope in ('session','referral')),
  session_id uuid unique references sessions(id),      -- scope=session: 대상 코칭 세션
  referral_id uuid unique,                              -- scope=referral: 대상 연계 (FK는 referrals 생성 뒤 추가)
  client_id uuid not null references users(id),
  trainee_id uuid references users(id),                 -- scope=session만 (연계는 report_sources로 수련생 확인)
  version smallint not null default 1,                  -- 연계 리포트: 동의 전 포함 회기를 바꾸면 +1
  status text not null default 'generating' check (status in ('generating','ready','failed')),
  content_enc bytea,
    -- session : {chief_complaint, distortions[{name, evidence}], triggers[], defenses[], red_flag{level, evidence}}
    -- referral: {chief_complaint, timeline[{session_no, session_date, trainee_label, key_points[], mood_before, mood_after}],
    --            distortions[{name, evidence, session_no}], triggers[], defenses[], progress,
    --            red_flag{max_level, history[{session_no, level, evidence}]}, focus_suggestions[]}
  client_summary_enc bytea,     -- 내담자용 쉬운 말 버전 (연계 리포트는 회기별 한 줄 포함)
  digest_enc bytea,             -- scope=session: 연계 리포트 재료용 구조화 요약 (16-2-1)
  risk_level text,
  model text,
  prompt_version text,
  attempts smallint not null default 0,
  error text,
  ready_at timestamptz,
  created_at timestamptz not null default now(),
  check ((scope = 'session'  and session_id is not null and referral_id is null)
      or (scope = 'referral' and referral_id is not null and session_id is null))
);

create table report_sources (    -- 연계 리포트에 포함된 코칭 회기
  report_id uuid not null references reports(id) on delete cascade,
  session_id uuid not null references sessions(id),
  session_no smallint not null,  -- 포함 회기 안에서의 시간순 번호 (1부터)
  created_at timestamptz not null default now(),
  primary key (report_id, session_id)
);

create table consents (
  id uuid primary key,
  user_id uuid not null references users(id),
  type text not null check (type in ('terms','privacy','sensitive','age14','training_data','marketing','report_transfer','transcript_transfer')),
  version text not null,
  target_expert_id uuid references users(id),
  report_id uuid references reports(id),          -- 연계 리포트(scope=referral)
  report_version smallint,                        -- 동의 당시 버전
  referral_id uuid,
  granted_at timestamptz not null default now(),
  revoked_at timestamptz,
  revoked_reason text,
  created_at timestamptz not null default now()
);
create index on consents (report_id, target_expert_id) where revoked_at is null;

create table referrals (
  id uuid primary key,
  client_id uuid not null references users(id),
  source_session_id uuid not null references sessions(id),   -- 연계를 시작한 회기 (권유·신청 시점의 최근 코칭)
  report_id uuid references reports(id),                      -- 연계 리포트 (scope=referral)
  included_locked_at timestamptz,                             -- 동의 시점. 이후 포함 회기 변경 불가
  initiated_by text not null check (initiated_by in ('client','trainee','ai')),
  trainee_reason text,
  expert_id uuid references users(id),
  expert_session_id uuid references sessions(id),
  status text not null default 'suggested'
    check (status in ('suggested','expert_selected','booked','accepted','declined','expired','cancelled')),
  decline_reason text,
  respond_by timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table reports add foreign key (referral_id) references referrals(id);

create table supervision_requests (
  id uuid primary key,
  trainee_id uuid not null references users(id),
  session_id uuid not null references sessions(id),
  preferred_expert_id uuid references users(id),
  assigned_expert_id uuid references users(id),
  question_enc bytea not null,
  fee int not null default 0,
  status text not null default 'pending' check (status in ('pending','accepted','completed','expired','cancelled')),
  accepted_at timestamptz,
  created_at timestamptz not null default now()
);

create table supervisions (
  id uuid primary key,
  kind text not null check (kind in ('referral','request')),
  referral_id uuid references referrals(id),            -- kind=referral
  report_id uuid references reports(id),                -- kind=referral: 연계 리포트
  request_id uuid references supervision_requests(id),  -- kind=request
  coaching_session_id uuid references sessions(id),     -- kind=request: 대상 회기 (referral은 report_sources로 수련생 회기 확인)
  expert_id uuid not null references users(id),
  trainee_id uuid not null references users(id),
  content_enc bytea,            -- {well_done, blind_spot, alternative_responses[{session_id, quote, suggestion}], risk_comment, next_action}
  risk_rating text check (risk_rating in ('appropriate','under','over')),
  score_rapport smallint,
  score_empathy smallint,
  score_risk smallint,
  score_structure smallint,
  status text not null default 'pending' check (status in ('pending','draft','submitted','cancelled')),
  due_at timestamptz,
  submitted_at timestamptz,
  read_at timestamptz,
  created_at timestamptz not null default now(),
  unique (referral_id, trainee_id),                     -- 연계 1건 × 수련생 1명당 1건
  unique (request_id),
  check ((kind = 'referral' and referral_id is not null)
      or (kind = 'request' and request_id is not null and coaching_session_id is not null))
);

create table certificates (
  id uuid primary key,
  trainee_id uuid not null references users(id),
  code text not null unique,     -- 12자 진위 확인 코드
  status text not null default 'generating' check (status in ('generating','ready','failed','revoked')),
  file_key text,
  hours numeric(6,1),
  session_count int,
  supervision_count int,
  issued_at timestamptz,
  created_at timestamptz not null default now()
);

-- =====================================================================
-- 11-4. 거래·운영
-- =====================================================================

create table payments (
  id uuid primary key,
  session_id uuid not null unique references sessions(id),
  client_id uuid not null references users(id),
  amount int not null,
  provider text not null check (provider in ('mock','toss')),
  order_id text not null unique,
  pg_payment_key text,
  status text not null default 'ready' check (status in ('ready','paid','cancelled','refunded','failed')),
  idempotency_key text not null unique,
  paid_at timestamptz,
  refunded_at timestamptz,
  refund_amount int,
  created_at timestamptz not null default now()
);

create table settlements (
  id uuid primary key,
  provider_id uuid not null references users(id),
  kind text not null check (kind in ('session','supervision')),
  session_id uuid references sessions(id),
  supervision_id uuid references supervisions(id),
  gross int not null,
  fee int not null,
  net int not null,
  status text not null check (status in ('locked','held','pending','paid')),
  -- locked: 그 연계의 슈퍼비전이 아직 다 제출되지 않음 / held: 스트라이크로 보류
  status_reason text,
  paid_at timestamptz,
  created_at timestamptz not null default now()
);

create table price_configs (     -- 운영 설정 key-value (가격·수수료·위기 연락처·당직·스크립트 등)
  key text primary key,          -- coaching | professional | supervision | supervision_request | refund_policy
                                 -- | crisis_contacts | oncall | crisis_scripts | referral_respond_hours | marketing_cost
                                 -- | referral_report {lookback_days, max_sessions, max_supervised_trainees}
  value jsonb not null,
  updated_by uuid references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table complaints (
  id uuid primary key,
  session_id uuid not null references sessions(id),
  reporter_id uuid references users(id),          -- null = 자동 접수
  target_id uuid not null references users(id),
  source text not null check (source in ('client','provider','auto_no_show','auto_contact')),
  category text not null check (category in ('late_noshow','religion_moralizing','private_contact','insincere',
                                             'diagnosis_demand','harassment','refund_abuse','other')),
  description_enc bytea,
  masked_transcript_enc bytea,
  auto_evidence jsonb,
  status text not null default 'received' check (status in ('received','in_review','decided')),
  decision text check (decision in ('trainee_fault','malicious','no_fault')),
  decision_reason text,
  decided_by uuid references users(id),
  decided_at timestamptz,
  created_at timestamptz not null default now(),
  unique (session_id, reporter_id)
);

create table strikes (
  id uuid primary key,
  trainee_id uuid not null references users(id),
  complaint_id uuid not null unique references complaints(id),
  seq smallint not null,                          -- 부여 시점의 유효 누적 번호
  action text not null check (action in ('warning_hold','suspend_2w','ban')),
  suspended_until timestamptz,
  revoked_at timestamptz,
  revoked_by uuid,
  revoke_reason text,
  created_at timestamptz not null default now()
);

create table client_sanctions (
  id uuid primary key,
  client_id uuid not null references users(id),
  complaint_id uuid not null unique references complaints(id),
  type text not null check (type in ('warning','restricted')),
  until timestamptz,
  lifted_at timestamptz,
  lifted_by uuid,
  created_at timestamptz not null default now()
);

create table notifications (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  type text not null,
  title text not null,
  body text not null,
  link text,
  payload jsonb,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index on notifications (user_id, created_at desc);

create table audit_logs (       -- 접속기록: 2년 이상 보관, 권한 변경 기록은 3년 (공통 6-6). app_rw는 SELECT·INSERT만
  id bigserial primary key,
  actor_id uuid,
  action text not null,                           -- view_report | view_transcript | decide_complaint | change_status ...
  target_type text not null,
  target_id uuid,
  basis_type text,
  basis_id uuid,                                  -- consent | complaint | risk_event
  reason text,
  ip inet,
  user_agent text,
  created_at timestamptz not null default now()
);

create table analytics_events (
  id bigserial primary key,
  eid text unique,                                -- 클라이언트 이벤트 ID (중복 전송 제거, 부록 B-7)
  user_id uuid,
  anon_id text,
  role text,
  name text not null,
  props jsonb not null default '{}',
  created_at timestamptz not null default now()
);
create index on analytics_events (name, created_at);
