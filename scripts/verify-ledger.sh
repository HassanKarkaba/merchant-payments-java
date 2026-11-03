#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
VERIFY_ID="mprp_java_verify_ledger_$(date +%s)_$$"
VERIFY_CONTAINER="$VERIFY_ID"
VERIFY_NETWORK="${VERIFY_ID}_network"
VERIFY_TESTS="${MPRP_VERIFY_TESTS:-PostgresLedgerWorkflowTest,WorkflowServiceIntegrationTest}"
VERIFY_IMAGE='postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5'
VERIFY_ADMIN_PASSWORD="$(openssl rand -hex 24)"
VERIFY_OWNER_PASSWORD="$(openssl rand -hex 24)"
VERIFY_RUNTIME_PASSWORD="$(openssl rand -hex 24)"
VERIFY_HEX="$(openssl rand -hex 16)"
VERIFY_ENVIRONMENT="$(printf '%s' "$VERIFY_HEX" | sed 's/^\(.\{8\}\)\(.\{4\}\)\(.\{4\}\)\(.\{4\}\)\(.\{12\}\)$/\1-\2-\3-\4-\5/')"
cleanup(){
 docker rm -f -v "$VERIFY_CONTAINER" >/dev/null 2>&1 || true
 docker network rm "$VERIFY_NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
docker network create --label mprp.verification="$VERIFY_ID" "$VERIFY_NETWORK" >/dev/null
docker run --detach --name "$VERIFY_CONTAINER" --network "$VERIFY_NETWORK" --label mprp.verification="$VERIFY_ID" -p 127.0.0.1::5432 -e POSTGRES_DB=mprp_java_verify -e POSTGRES_PASSWORD="$VERIFY_ADMIN_PASSWORD" "$VERIFY_IMAGE" >/dev/null
TRIES=0
until docker exec "$VERIFY_CONTAINER" psql -U postgres -d mprp_java_verify -tAc 'SELECT 1' >/dev/null 2>&1; do
 TRIES=$((TRIES+1)); [ "$TRIES" -lt 45 ] || exit 1; sleep 1
done
docker exec -i "$VERIFY_CONTAINER" psql -v ON_ERROR_STOP=1 -U postgres -d mprp_java_verify <<SQL
CREATE ROLE mprp_owner LOGIN PASSWORD '$VERIFY_OWNER_PASSWORD';
CREATE ROLE mprp_runtime LOGIN PASSWORD '$VERIFY_RUNTIME_PASSWORD';
ALTER DATABASE mprp_java_verify OWNER TO mprp_owner;
ALTER SCHEMA public OWNER TO mprp_owner;
REVOKE TEMPORARY ON DATABASE mprp_java_verify FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
VERIFY_PORT="$(docker port "$VERIFY_CONTAINER" 5432/tcp | cut -d: -f2)"
export MPRP_MAIN_TEST_JDBC="jdbc:postgresql://127.0.0.1:$VERIFY_PORT/mprp_java_verify"
export MPRP_MAIN_TEST_OWNER_PASSWORD="$VERIFY_OWNER_PASSWORD"
export MPRP_MAIN_TEST_RUNTIME_PASSWORD="$VERIFY_RUNTIME_PASSWORD"
export MPRP_MAIN_TEST_ENVIRONMENT_ID="$VERIFY_ENVIRONMENT"
printf 'Verification resource: %s; network: %s; PostgreSQL image: %s; loopback port: %s; environment: %s\n' "$VERIFY_CONTAINER" "$VERIFY_NETWORK" "$VERIFY_IMAGE" "$VERIFY_PORT" "$VERIFY_ENVIRONMENT"
./mvnw -B -ntp -pl application -am test -Dtest="$VERIFY_TESTS" -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.forkedProcessTimeoutInSeconds=300
