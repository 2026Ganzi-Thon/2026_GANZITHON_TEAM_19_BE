#!/usr/bin/env bash
# 운영 서버(EC2)에서 SSM으로 실행된다 (root). GitHub Actions deploy job 참고.
#   사용법: deploy.sh <이미지 태그(커밋 SHA)> <ECR 레포 주소>
# 1) Parameter Store에서 비밀값 → .env, Firebase 키 파일
# 2) 새 이미지로 compose up → /v1/health 확인
# 3) 실패하면 직전 릴리스로 되돌리고 실패로 끝남
set -euo pipefail

TAG="$1"
IMAGE_REPO="$2"
REGION=ap-northeast-2
BASE=/opt/mindcare
REL="$BASE/releases/$TAG"
CURRENT="$BASE/current"
HEALTH_URL=http://127.0.0.1:8090/v1/health

param() {
  aws ssm get-parameter --region "$REGION" --name "/mindcare/prod/$1" \
    --with-decryption --query Parameter.Value --output text
}

compose() {
  local dir="$1"; shift
  docker compose -p mindcare -f "$dir/docker-compose.prod.yml" --env-file "$dir/.env" "$@"
}

echo "== 배포 시작: $TAG"
umask 077
mkdir -p "$BASE/secrets"

# Firebase 키: 컨테이너 안 app 사용자(UID 1001)만 읽을 수 있게
param FIREBASE_SERVICE_ACCOUNT > "$BASE/secrets/firebase.json"
chown 1001:1001 "$BASE/secrets/firebase.json"
chmod 400 "$BASE/secrets/firebase.json"

cat > "$REL/.env" <<EOF
APP_IMAGE=$IMAGE_REPO:$TAG
DB_PASSWORD=$(param DB_PASSWORD)
APP_ENCRYPTION_KEY=$(param APP_ENCRYPTION_KEY)
APP_HMAC_KEY=$(param APP_HMAC_KEY)
CORS_ORIGINS=$(param CORS_ORIGINS)
EOF

echo "== 이미지 받기"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "${IMAGE_REPO%%/*}"
compose "$REL" pull app

echo "== 컨테이너 교체"
compose "$REL" up -d --remove-orphans

echo "== 헬스체크 (최대 150초)"
for _ in $(seq 1 30); do
  if curl -fsS "$HEALTH_URL" > /dev/null 2>&1; then
    ln -sfn "$REL" "$CURRENT"
    docker image prune -f > /dev/null
    # 릴리스 폴더는 최근 5개만 보관 (ECR 수명 주기와 맞춤)
    ls -1dt "$BASE"/releases/*/ | tail -n +6 | xargs -r rm -rf
    echo "== 배포 성공: $TAG"
    exit 0
  fi
  sleep 5
done

echo "!! 헬스체크 실패. app 로그 마지막 80줄:"
compose "$REL" logs --tail 80 app || true

if [ -L "$CURRENT" ] && [ "$(readlink -f "$CURRENT")" != "$REL" ]; then
  PREV="$(readlink -f "$CURRENT")"
  echo "!! 직전 릴리스로 롤백: $(basename "$PREV")"
  compose "$PREV" up -d --remove-orphans
else
  echo "!! 되돌릴 직전 릴리스가 없음 (첫 배포)"
fi
exit 1
