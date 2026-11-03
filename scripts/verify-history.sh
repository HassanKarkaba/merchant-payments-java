#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
VERIFY_ID="mprp_java_verify_history_$(openssl rand -hex 8)"
VERIFY_IMAGE='postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5'
VERIFY_ADMIN_PASSWORD="$(openssl rand -hex 32)"
VERIFY_OWNER_PASSWORD="$(openssl rand -hex 32)"
VERIFY_RUNTIME_PASSWORD="$(openssl rand -hex 32)"
cleanup(){ docker rm -f -v "$VERIFY_ID" >/dev/null 2>&1 || true; }
trap cleanup EXIT HUP INT TERM
docker run --detach --name "$VERIFY_ID" --label mprp.verification="$VERIFY_ID" -p 127.0.0.1::5432 -e POSTGRES_DB=mprp_java_verify -e POSTGRES_PASSWORD="$VERIFY_ADMIN_PASSWORD" "$VERIFY_IMAGE" >/dev/null
VERIFY_TRIES=0
until docker exec "$VERIFY_ID" psql -U postgres -d mprp_java_verify -tAc 'SELECT 1' >/dev/null 2>&1; do
  VERIFY_TRIES=$((VERIFY_TRIES+1)); [ "$VERIFY_TRIES" -lt 45 ] || exit 1; sleep 1
done
docker exec -i "$VERIFY_ID" psql -v ON_ERROR_STOP=1 -U postgres -d mprp_java_verify <<SQL
CREATE ROLE mprp_owner LOGIN PASSWORD '$VERIFY_OWNER_PASSWORD';
CREATE ROLE mprp_runtime LOGIN PASSWORD '$VERIFY_RUNTIME_PASSWORD';
ALTER DATABASE mprp_java_verify OWNER TO mprp_owner;
ALTER SCHEMA public OWNER TO mprp_owner;
REVOKE TEMPORARY ON DATABASE mprp_java_verify FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
VERIFY_PORT="$(docker port "$VERIFY_ID" 5432/tcp | cut -d: -f2)"
export MPRP_MAIN_TEST_JDBC="jdbc:postgresql://127.0.0.1:$VERIFY_PORT/mprp_java_verify"
export MPRP_MAIN_TEST_OWNER_PASSWORD="$VERIFY_OWNER_PASSWORD"
export MPRP_MAIN_TEST_RUNTIME_PASSWORD="$VERIFY_RUNTIME_PASSWORD"
printf 'Verification resource: %s; PostgreSQL image: %s; loopback port: %s\n' "$VERIFY_ID" "$VERIFY_IMAGE" "$VERIFY_PORT"
./mvnw -B -ntp -pl application -am test -Dtest=ProviderHistoryIntegrationTest,MerchantClosureIntegrationTest,ControlDecisionReplayTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.forkedProcessTimeoutInSeconds=600 "$@"
