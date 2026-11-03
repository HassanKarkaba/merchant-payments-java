#!/bin/sh
# Explicitly isolated provider PostgreSQL boundary tests. No development resources are selected.
set -eu
cd "$(dirname "$0")/.."
command -v docker >/dev/null
name="mprp_java_verify_sim_$(date +%s)_$$"
owner_secret=$(openssl rand -hex 24)
runtime_secret=$(openssl rand -hex 24)
purge_secret=$(openssl rand -hex 24)
printf 'Isolated simulator verification resource: %s\n' "$name"
cleanup() { docker rm -f -v "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT HUP INT TERM
docker run -d --name "$name" --label mprp.purpose=isolated-verification -e POSTGRES_USER=simulator_owner -e POSTGRES_PASSWORD="$owner_secret" -e POSTGRES_DB=simulator -p 127.0.0.1::5432 postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5 >/dev/null
attempt=0
until docker exec "$name" pg_isready -h 127.0.0.1 -U simulator_owner -d simulator >/dev/null 2>&1; do
 attempt=$((attempt+1)); [ "$attempt" -lt 60 ] || { echo 'Isolated PostgreSQL did not become ready' >&2; exit 1; }
 sleep 1
done
docker exec -i "$name" psql -v ON_ERROR_STOP=1 -U simulator_owner -d simulator <<SQL
CREATE ROLE simulator_runtime LOGIN PASSWORD '$runtime_secret';
CREATE ROLE simulator_purge LOGIN PASSWORD '$purge_secret';
SQL
binding=$(docker port "$name" 5432/tcp)
port=${binding##*:}
export TEST_SIMULATOR_DB_URL="jdbc:postgresql://127.0.0.1:$port/simulator"
export TEST_SIMULATOR_DB_PASSWORD="$runtime_secret"
export TEST_SIMULATOR_OWNER_PASSWORD="$owner_secret"
export TEST_SIMULATOR_PURGE_PASSWORD="$purge_secret"
"${MPRP_MAVEN:-./mvnw}" -pl simulator -am test -Dtest=ProtocolTest,SimulatorPostgresTest -Dsurefire.failIfNoSpecifiedTests=false "$@"
