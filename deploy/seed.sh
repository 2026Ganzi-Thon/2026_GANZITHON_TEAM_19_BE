#!/usr/bin/env bash
# 운영 서버(EC2)에서 시연용 시드를 넣는다 (TRD 19-3). SSM 세션에서 root로 실행.
#   사용법: bash /opt/mindcare/current/seed.sh [같이 지울 Firebase uid,...]
# - 지금 떠 있는 릴리스 이미지로 일회성 컨테이너를 띄워 --app.task=seed 실행 (웹 서버 안 뜸, 끝나면 컨테이너 삭제)
# - demo- 계정과 그 데이터만 지우고 다시 넣는다. 실제 가입자 데이터는 건드리지 않음
# - 비밀번호는 Parameter Store /mindcare/prod/SEED_PASSWORD (명령줄·로그에 안 남게 환경변수로만 넘김)
set -euo pipefail

REGION=ap-northeast-2
CURRENT=/opt/mindcare/current

SEED_PASSWORD="$(aws ssm get-parameter --region "$REGION" --name /mindcare/prod/SEED_PASSWORD \
  --with-decryption --query Parameter.Value --output text)"
SEED_PURGE_UIDS="${1:-}"
export SEED_PASSWORD SEED_PURGE_UIDS

docker compose -p mindcare -f "$CURRENT/docker-compose.prod.yml" --env-file "$CURRENT/.env" \
  run --rm --no-deps -e SEED_PASSWORD -e SEED_PURGE_UIDS app --app.task=seed
