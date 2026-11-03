#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
ENV_FILE=${MPRP_ENV_FILE:-$ROOT/.local/environment.env}
init(){
 umask 077
 [ ! -e "$ENV_FILE" ] || { echo 'Private configuration already exists; refusing overwrite.' >&2; exit 1; }
 mkdir -p "$(dirname "$ENV_FILE")"
 ENV_ID=$(openssl rand -hex 16 | sed 's/^\(.\{8\}\)\(.\{4\}\)\(.\{4\}\)\(.\{4\}\)\(.\{12\}\)$/\1-\2-\3-\4-\5/')
 { printf 'COMPOSE_PROJECT_NAME=mprp_java_%s\n' "$(printf '%s' "$ENV_ID" | cut -c1-8)"; printf 'MPRP_ENVIRONMENT_ID=%s\n' "$ENV_ID"; printf 'MPRP_SERVICE_KEY_ACTIVE_FROM=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"; printf 'MPRP_SERVICE_KEY_EXPIRES_AT=%s\n' "$(date -u -d '+365 days' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+365d +%Y-%m-%dT%H:%M:%SZ)"; printf 'WEB_PORT=%s\nMAIL_PORT=%s\n' "${WEB_PORT:-8180}" "${MAIL_PORT:-8026}"; for name in MAIN_ADMIN_PASSWORD MAIN_OWNER_PASSWORD MAIN_RUNTIME_PASSWORD SIM_ADMIN_PASSWORD SIM_OWNER_PASSWORD SIM_RUNTIME_PASSWORD MAIN_PURGE_PASSWORD SIM_PURGE_PASSWORD MPRP_PURGE_SECRET MPRP_SERVICE_SECRET; do printf '%s=%s\n' "$name" "$(openssl rand -hex 32)"; done; printf 'MPRP_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"; printf 'KAFKA_CLUSTER_ID=%s\n' "$(openssl rand -base64 16 | tr '+/' '-_' | tr -d '=\n')"; } > "$ENV_FILE"
 chmod 600 "$ENV_FILE"
 echo 'Initialized private fictional sandbox configuration.'
}
compose(){ docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml" "$@"; }
command=${1:-help}
if [ "$command" = init ]; then init; exit; fi
[ -f "$ENV_FILE" ] || { echo 'Run sh scripts/local.sh init first.' >&2; exit 1; }
set -a
. "$ENV_FILE"
set +a
case "$command" in
 up|restart|migrate|seed-demo|rotate-encryption|backup)
  if [ "$command" != backup ]; then
   umask 077
   . "$ROOT/scripts/environment-lock.sh"
   trap environment_release EXIT
   trap 'exit 130' INT
   trap 'exit 129' HUP
   trap 'exit 143' TERM
   environment_acquire
  fi
  [ ! -e "$ENV_FILE.restore-in-progress" ] && [ ! -L "$ENV_FILE.restore-in-progress" ] || { echo 'An interrupted paired restore blocks startup. Rerun local.sh restore with its exact retained backup; keep effect services stopped.' >&2; exit 1; }
  ;;
esac
case "$command" in
 migrate)
  compose stop -t 35 web worker simulator simulator-worker
  compose build web simulator main-postgres simulator-postgres
  compose up -d main-postgres simulator-postgres kafka mailpit
  compose run --rm --no-deps -e MPRP_DB_USER=mprp_owner -e MPRP_DB_PASSWORD="$MAIN_OWNER_PASSWORD" -e SPRING_FLYWAY_ENABLED=true -e MPRP_COMMAND=migrate -e SPRING_KAFKA_ADMIN_AUTO_CREATE=false web
  compose run --rm --no-deps -e SIMULATOR_DB_USER=simulator_owner -e SIMULATOR_DB_PASSWORD="$SIM_OWNER_PASSWORD" -e SIMULATOR_MIGRATION_USER=simulator_owner -e SIMULATOR_MIGRATION_PASSWORD="$SIM_OWNER_PASSWORD" -e MIGRATE=true -e MPRP_COMMAND=migrate simulator
  ;;
 rotate-encryption)
  compose stop -t 35 web worker
  sleep 31
  compose run --rm --no-deps -e MPRP_COMMAND=rotate-encryption -e SPRING_KAFKA_ADMIN_AUTO_CREATE=false web
  compose up -d web worker
  ;;
 seed-demo)
  DEMO_DIR=${MPRP_DEMO_DIR:-$ROOT/.local}
  mkdir -p "$DEMO_DIR"
  chmod 700 "$DEMO_DIR"
  compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$DEMO_DIR:/data" -e MPRP_DEMO_ACCESS_PATH=/data/demo-access.txt -e MPRP_COMMAND=seed-demo -e SPRING_KAFKA_ADMIN_AUTO_CREATE=false web
  echo 'Private credentials: .local/demo-access.txt (never include in delivery).'
  ;;
 up) compose up -d web worker simulator simulator-worker kafka mailpit main-postgres simulator-postgres ;;
 health) curl --fail --max-time 5 "http://127.0.0.1:$WEB_PORT/health/ready/"; printf '\n' ;;
 logs) compose logs --tail=150 "${2:-web}" ;;
 stop) compose stop -t 35 ;;
 restart) compose stop -t 35 web worker simulator simulator-worker; compose up -d web worker simulator simulator-worker ;;
 backup) sh scripts/recovery.sh backup ;;
 restore) sh scripts/recovery.sh restore "${2:?Supply backup directory}" ;;
 verify) sh scripts/verify.sh ;;
 *) echo 'Commands: init migrate seed-demo up health logs stop restart backup restore verify rotate-encryption'; exit 2 ;;
esac
