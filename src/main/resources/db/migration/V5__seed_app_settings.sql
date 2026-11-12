-- TRD 7-8 기능 플래그·점검 모드 기본값 (PUB-02로 프론트가 읽고, 어드민 운영 설정 ADM-07에서 바꾼다).
insert into price_configs (key, value) values
  ('feature_flags',      '{"p1_certificates": false, "p1_supervision_requests": false, "p1_complaints": false, "p1_credential_auto_verify": false}'),
  ('maintenance',        '{"on": false, "message": null, "until": null}'),
  ('min_client_version', '"2026.10.26"')
on conflict (key) do nothing;
