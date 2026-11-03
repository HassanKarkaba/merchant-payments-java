#!/bin/sh
set -eu
umask 077
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
VERIFY_ID=$(openssl rand -hex 8)
EVIDENCE_ROOT=${MPRP_VERIFY_EVIDENCE_DIR:-$(dirname "$ROOT")/merchant-payments-verification-$VERIFY_ID}
mkdir -p "$EVIDENCE_ROOT/private"
export MPRP_ENV_FILE="$EVIDENCE_ROOT/private/environment.env"
export MPRP_DEMO_DIR="$EVIDENCE_ROOT/private"
export WEB_PORT=0 MAIL_PORT=0
sh scripts/local.sh init
PROJECT="mprp_java_verify_$VERIFY_ID"
sed "s/^COMPOSE_PROJECT_NAME=.*/COMPOSE_PROJECT_NAME=$PROJECT/" "$MPRP_ENV_FILE" > "$MPRP_ENV_FILE.new"
mv "$MPRP_ENV_FILE.new" "$MPRP_ENV_FILE"
compose(){ docker compose --env-file "$MPRP_ENV_FILE" -p "$PROJECT" -f "$ROOT/compose.yaml" "$@"; }
cleanup(){ compose logs --no-color > "$EVIDENCE_ROOT/compose.log" 2>&1 || true; compose ps -a > "$EVIDENCE_ROOT/resources.txt" 2>&1 || true; compose down --volumes --remove-orphans > "$EVIDENCE_ROOT/cleanup.log" 2>&1 || true; }
trap cleanup EXIT HUP INT TERM
sh scripts/local.sh migrate > "$EVIDENCE_ROOT/migrate.log" 2>&1
sh scripts/local.sh seed-demo > "$EVIDENCE_ROOT/seed.log" 2>&1
sh scripts/local.sh up > "$EVIDENCE_ROOT/start.log" 2>&1
ACTUAL_WEB_PORT=$(compose port web 8180 | sed 's/.*://')
ACTUAL_MAIL_PORT=$(compose port mailpit 8025 | sed 's/.*://')
DEADLINE=$(( $(date +%s) + 180 ))
while ! curl --fail --silent --max-time 5 "http://127.0.0.1:$ACTUAL_WEB_PORT/health/ready/" > "$EVIDENCE_ROOT/readiness.json"; do
 [ "$(date +%s)" -lt "$DEADLINE" ] || { echo 'Finite readiness deadline exceeded' >&2; exit 1; }
 sleep 2
done
while ! grep -q '"financial_admission":true' "$EVIDENCE_ROOT/readiness.json"; do
 [ "$(date +%s)" -lt "$DEADLINE" ] || { echo 'Financial readiness deadline exceeded' >&2; exit 1; }
 sleep 2
 curl --fail --silent --max-time 5 "http://127.0.0.1:$ACTUAL_WEB_PORT/health/ready/" > "$EVIDENCE_ROOT/readiness.json"
done
export MPRP_BROWSER_ISOLATED=true
export MPRP_BROWSER_BASE_URL="http://127.0.0.1:$ACTUAL_WEB_PORT"
export MPRP_MAILPIT_BASE_URL="http://127.0.0.1:$ACTUAL_MAIL_PORT"
export MPRP_DEMO_ACCESS_PATH="$EVIDENCE_ROOT/private/demo-access.txt"
export MPRP_BROWSER_EVIDENCE_DIR="$EVIDENCE_ROOT/browser"
sh scripts/verify-browser.sh > "$EVIDENCE_ROOT/browser.log" 2>&1
compose images > "$EVIDENCE_ROOT/images.txt"
printf 'Isolated verification passed. Evidence: %s\n' "$EVIDENCE_ROOT"
