#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
ENV_FILE=${MPRP_ENV_FILE:-$ROOT/.local/environment.env}
[ -f "$ENV_FILE" ] || { echo 'Run local.sh init and migrate first.' >&2; exit 2; }
set -a
. "$ENV_FILE"
set +a
compose(){ docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml" "$@"; }
absolute_file(){ [ -f "$1" ] && [ ! -L "$1" ] || { echo 'A regular private file is required.' >&2; exit 2; }; FILE_DIR=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd); printf '%s/%s' "$FILE_DIR" "$(basename -- "$1")"; }
COMMAND=${1:-}
case "$COMMAND" in
 bootstrap-staff)
  [ "$#" -eq 6 ] || [ "$#" -eq 8 ] || { echo 'Usage: account-admin.sh bootstrap-staff EMAIL ROLE OPERATOR_UUID REASON NEW_PASSWORD_FILE [OPERATOR_PASSWORD_FILE OPERATOR_TOTP_FILE]' >&2; exit 2; }
  EMAIL=$2 ROLE=$3 OPERATOR=$4 REASON=$5 NEW_FILE=$(absolute_file "$6")
  if [ "$#" -eq 8 ]; then OP_PASSWORD=$(absolute_file "$7"); OP_TOTP=$(absolute_file "$8"); else OP_PASSWORD=$NEW_FILE; OP_TOTP=$NEW_FILE; fi
  compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$NEW_FILE:/run/account-new-password:ro" -v "$OP_PASSWORD:/run/account-operator-password:ro" -v "$OP_TOTP:/run/account-operator-totp:ro" -e SPRING_MAIN_WEB_APPLICATION_TYPE=none -e SPRING_KAFKA_ADMIN_AUTO_CREATE=false -e MPRP_COMMAND=bootstrap-staff -e MPRP_DB_USER=mprp_owner -e MPRP_DB_PASSWORD="$MAIN_OWNER_PASSWORD" -e MPRP_ACCOUNT_EMAIL="$EMAIL" -e MPRP_ACCOUNT_ROLE="$ROLE" -e MPRP_OPERATOR_ID="$OPERATOR" -e MPRP_ACCOUNT_REASON="$REASON" -e MPRP_NEW_PASSWORD_FILE=/run/account-new-password -e MPRP_OPERATOR_PASSWORD_FILE=/run/account-operator-password -e MPRP_OPERATOR_TOTP_FILE=/run/account-operator-totp web
  ;;
 recover-mfa)
  [ "$#" -eq 6 ] || { echo 'Usage: account-admin.sh recover-mfa EMAIL OPERATOR_UUID REASON OPERATOR_PASSWORD_FILE OPERATOR_TOTP_FILE' >&2; exit 2; }
  EMAIL=$2 OPERATOR=$3 REASON=$4 OP_PASSWORD=$(absolute_file "$5") OP_TOTP=$(absolute_file "$6")
  compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$OP_PASSWORD:/run/account-operator-password:ro" -v "$OP_TOTP:/run/account-operator-totp:ro" -e SPRING_MAIN_WEB_APPLICATION_TYPE=none -e SPRING_KAFKA_ADMIN_AUTO_CREATE=false -e MPRP_COMMAND=recover-mfa -e MPRP_DB_USER=mprp_owner -e MPRP_DB_PASSWORD="$MAIN_OWNER_PASSWORD" -e MPRP_ACCOUNT_EMAIL="$EMAIL" -e MPRP_OPERATOR_ID="$OPERATOR" -e MPRP_ACCOUNT_REASON="$REASON" -e MPRP_ACCOUNT_OWNER_RECOVERY="${MPRP_ACCOUNT_OWNER_RECOVERY:-false}" -e MPRP_OPERATOR_PASSWORD_FILE=/run/account-operator-password -e MPRP_OPERATOR_TOTP_FILE=/run/account-operator-totp web
  ;;
 *) echo 'Commands: bootstrap-staff, recover-mfa. Secret inputs are owner-only files; never pass passwords or codes as command arguments.' >&2; exit 2;;
esac
