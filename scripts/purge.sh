#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
ENV_FILE=${MPRP_ENV_FILE:-$ROOT/.local/environment.env}
set -a
. "$ENV_FILE"
set +a
compose(){ docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml" "$@"; }
[ "$#" -eq 4 ] || { echo 'Usage: sh scripts/purge.sh PURGE_ID MERCHANT_UUID PROVIDER_INVENTORY_HASH BACKUP_DIRECTORY' >&2; exit 2; }
PURGE_ID=$1
MERCHANT_ID=$2
INVENTORY_HASH=$3
BACKUP_DIRECTORY=$4
[ -f "$BACKUP_DIRECTORY/environment-id" ] && [ "$(cat "$BACKUP_DIRECTORY/environment-id")" = "$MPRP_ENVIRONMENT_ID" ] || { echo 'Explicit backup store inventory does not belong to this environment.' >&2; exit 1; }
printf 'Permanently retire and purge merchant %s. Type its UUID: ' "$MERCHANT_ID"
IFS= read -r CONFIRM
[ "$CONFIRM" = "$MERCHANT_ID" ] || { echo 'Cancelled.'; exit 1; }
compose run --rm --no-deps -e SIMULATOR_DB_USER=simulator_owner -e SIMULATOR_DB_PASSWORD="$SIM_OWNER_PASSWORD" -e MPRP_PURGE_SECRET="$MPRP_PURGE_SECRET" -e MPRP_COMMAND=configure-purge simulator
compose run --rm --no-deps -e MPRP_DB_USER=mprp_purge -e MPRP_DB_PASSWORD="$MAIN_PURGE_PASSWORD" -e MPRP_PURGE_SECRET="$MPRP_PURGE_SECRET" -e MPRP_PURGE_ID="$PURGE_ID" -e MPRP_COMMAND=purge-retire web
compose run --rm --no-deps -e SIMULATOR_DB_USER=simulator_purge -e SIMULATOR_DB_PASSWORD="$SIM_PURGE_PASSWORD" -e MPRP_PURGE_SECRET="$MPRP_PURGE_SECRET" -e MPRP_COMMAND=purge simulator --mprp.purge-account="$MERCHANT_ID" --mprp.purge-approval="$PURGE_ID" --mprp.purge-inventory-hash="$INVENTORY_HASH"
compose run --rm --no-deps -e MPRP_DB_USER=mprp_purge -e MPRP_DB_PASSWORD="$MAIN_PURGE_PASSWORD" -e MPRP_PURGE_ID="$PURGE_ID" -e MPRP_COMMAND=purge-execute web
