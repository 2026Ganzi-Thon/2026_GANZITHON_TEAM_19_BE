-- TRD 19-3 price_configs 기본값. 금액은 시연용 가정치이며 개요서 7-3에서 확정한다 (어드민 운영 설정 M-10에서 수정).
-- 슬롯 길이(duration_min)와 예약 가격(price)을 여기서 읽으므로 기본값이 반드시 있어야 한다.
insert into price_configs (key, value) values
  ('coaching',               '{"price": 15000, "duration_min": 30, "fee_rate": 0.2}'),
  ('professional',           '{"price": 80000, "duration_min": 50, "fee_rate": 0.2}'),
  ('supervision',            '{"price": 20000}'),
  ('supervision_request',    '{"price": 0}'),
  ('refund_policy',          '{"full_refund_hours": 24}'),
  ('referral_respond_hours', '24'),
  ('referral_report',        '{"lookback_days": 90, "max_sessions": 10, "max_supervised_trainees": 3}'),
  ('crisis_contacts',        '[{"name": "자살예방 상담전화", "number": "109", "description": "24시간 자살 위기 상담"},
                               {"name": "정신건강 위기상담 전화", "number": "1577-0199", "description": "24시간 정신건강 위기 상담"},
                               {"name": "경찰", "number": "112", "description": "긴급 신고"},
                               {"name": "119", "number": "119", "description": "응급 구조"}]')
on conflict (key) do nothing;
