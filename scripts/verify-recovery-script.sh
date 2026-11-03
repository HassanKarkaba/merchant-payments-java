#!/bin/sh
# Shell control-flow test only: no Docker daemon, database, broker or development resource is used.
set -eu
umask 077
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERIFY_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mprp_java_verify_restore_scripts.XXXXXX")
VERIFY_UP_PID=
cleanup(){ if [ -n "$VERIFY_UP_PID" ]; then : > "$VERIFY_ROOT/up-release"; wait "$VERIFY_UP_PID" || true; fi; rm -rf "$VERIFY_ROOT"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 129' HUP
trap 'exit 143' TERM
mkdir -p "$VERIFY_ROOT/scripts" "$VERIFY_ROOT/bin" "$VERIFY_ROOT/.local" "$VERIFY_ROOT/backup"
cp "$ROOT/scripts/recovery.sh" "$ROOT/scripts/local.sh" "$ROOT/scripts/environment-lock.sh" "$VERIFY_ROOT/scripts/"
printf 'services: {}\n' > "$VERIFY_ROOT/compose.yaml"
VERIFY_ENVIRONMENT=11111111-2222-4333-8444-555555555555
export MPRP_ENV_FILE="$VERIFY_ROOT/.local/environment.env"
printf 'MPRP_ENVIRONMENT_ID=%s\nKAFKA_CLUSTER_ID=fictional-test-cluster\nWEB_PORT=1\n' "$VERIFY_ENVIRONMENT" > "$MPRP_ENV_FILE"
printf '%s\n' "$VERIFY_ENVIRONMENT" > "$VERIFY_ROOT/backup/environment-id"
printf 'fictional-main-dump\n' > "$VERIFY_ROOT/backup/main.dump"
printf 'fictional-simulator-dump\n' > "$VERIFY_ROOT/backup/simulator.dump"
export VERIFY_DOCKER_LOG="$VERIFY_ROOT/docker.log"
cat > "$VERIFY_ROOT/bin/docker" <<'MOCK'
#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$VERIFY_DOCKER_LOG"
case "$*" in
 *pg_restore*|*'psql -U postgres'*'restore_hold=true'*|*'psql -U postgres'*'restored=true'*)
  [ -f "$MPRP_ENV_FILE.restore-in-progress" ] || exit 90;;
esac
case "$*" in
 *'simulator-postgres timeout'*pg_restore*) [ "${VERIFY_FAIL_STAGE:-}" != second-import ] || exit 31;;
 *'main-postgres timeout'*'restore_hold=true'*) [ "${VERIFY_FAIL_STAGE:-}" != main-hold ] || exit 32;;
 *'simulator-postgres timeout'*'restored=true'*) [ "${VERIFY_FAIL_STAGE:-}" != simulator-hold ] || exit 33;;
 *'up -d'*)
  [ ! -e "$MPRP_ENV_FILE.restore-in-progress" ] || exit 91
  if [ -n "${VERIFY_UP_READY:-}" ]; then
   : > "$VERIFY_UP_READY"
   VERIFY_GATE_TRIES=0
   until [ -f "$VERIFY_UP_RELEASE" ]; do VERIFY_GATE_TRIES=$((VERIFY_GATE_TRIES+1)); [ "$VERIFY_GATE_TRIES" -lt 20 ] || exit 92; sleep 1; done
  fi;;
esac
MOCK
chmod 700 "$VERIFY_ROOT/bin/docker"
PATH="$VERIFY_ROOT/bin:$PATH"
export PATH
restore(){ printf '%s\n' "$VERIFY_ENVIRONMENT" | sh "$VERIFY_ROOT/scripts/recovery.sh" restore "$1" > "$VERIFY_ROOT/result.log" 2>&1; }
expect_failure(){ if "$@"; then echo 'Expected rejection was accepted.' >&2; exit 1; fi; }
MARKER="$MPRP_ENV_FILE.restore-in-progress"
LOCK="$MPRP_ENV_FILE.action-lock"
for VERIFY_FAIL_STAGE in second-import main-hold simulator-hold; do
 export VERIFY_FAIL_STAGE
 : > "$VERIFY_DOCKER_LOG"
 expect_failure restore "$VERIFY_ROOT/backup"
 [ -f "$MARKER" ] && [ ! -e "$LOCK" ]
 case "$(cat "$VERIFY_DOCKER_LOG")" in *'up -d'*) echo 'Interrupted restore restarted effects.' >&2; exit 1;; esac
 cp "$VERIFY_DOCKER_LOG" "$VERIFY_ROOT/before-blocked.log"
 for VERIFY_COMMAND in up restart migrate seed-demo rotate-encryption backup; do
  expect_failure sh "$VERIFY_ROOT/scripts/local.sh" "$VERIFY_COMMAND" > "$VERIFY_ROOT/rejection.log" 2>&1
 done
 cmp -s "$VERIFY_DOCKER_LOG" "$VERIFY_ROOT/before-blocked.log"
 # Mutating a dump at the same path cannot turn resume into a different restore.
 cp "$VERIFY_ROOT/backup/main.dump" "$VERIFY_ROOT/original.dump"
 printf 'changed\n' >> "$VERIFY_ROOT/backup/main.dump"
 expect_failure restore "$VERIFY_ROOT/backup"
 cmp -s "$VERIFY_DOCKER_LOG" "$VERIFY_ROOT/before-blocked.log"
 mv "$VERIFY_ROOT/original.dump" "$VERIFY_ROOT/backup/main.dump"
 # A live owned process lock prevents concurrent restoration.
 mkdir "$LOCK"
 printf '%s\n' "$$" > "$LOCK/pid"
 expect_failure restore "$VERIFY_ROOT/backup"
 [ "$(cat "$LOCK/pid")" = "$$" ]
 rm "$LOCK/pid"
 rmdir "$LOCK"
 VERIFY_FAIL_STAGE=
 export VERIFY_FAIL_STAGE
 restore "$VERIFY_ROOT/backup"
 [ ! -e "$MARKER" ] && [ ! -e "$LOCK" ]
 grep -q -- '--single-transaction' "$VERIFY_DOCKER_LOG"
 grep -q 'restore_requested=true' "$VERIFY_DOCKER_LOG"
 grep -q 'restored=true' "$VERIFY_DOCKER_LOG"
 grep -q 'up -d web worker simulator simulator-worker' "$VERIFY_DOCKER_LOG"
done
# Hold startup inside the controlled Compose boundary; restore must not interleave its stop/import.
: > "$VERIFY_DOCKER_LOG"
VERIFY_UP_READY="$VERIFY_ROOT/up-ready" VERIFY_UP_RELEASE="$VERIFY_ROOT/up-release" sh "$VERIFY_ROOT/scripts/local.sh" up > "$VERIFY_ROOT/up-result.log" 2>&1 &
VERIFY_UP_PID=$!
VERIFY_GATE_TRIES=0
until [ -f "$VERIFY_ROOT/up-ready" ]; do VERIFY_GATE_TRIES=$((VERIFY_GATE_TRIES+1)); [ "$VERIFY_GATE_TRIES" -lt 10 ] || exit 1; sleep 1; done
cp "$VERIFY_DOCKER_LOG" "$VERIFY_ROOT/before-concurrent.log"
expect_failure restore "$VERIFY_ROOT/backup"
cmp -s "$VERIFY_DOCKER_LOG" "$VERIFY_ROOT/before-concurrent.log"
[ ! -e "$MARKER" ]
: > "$VERIFY_ROOT/up-release"
wait "$VERIFY_UP_PID"
VERIFY_UP_PID=
[ ! -e "$LOCK" ]
# A completed owned process can leave a stale lock, as with abrupt process loss.
sh -c 'exit 0' &
VERIFY_DEAD_PID=$!
wait "$VERIFY_DEAD_PID"
mkdir "$LOCK"
printf '%s\n' "$VERIFY_DEAD_PID" > "$LOCK/pid"
restore "$VERIFY_ROOT/backup"
[ ! -e "$MARKER" ] && [ ! -e "$LOCK" ] && [ ! -e "$MPRP_ENV_FILE.action-lock-reaper" ]
mkdir -p "$VERIFY_ROOT/.local/backups"
VERIFY_OWN_OLD="$VERIFY_ROOT/.local/backups/20200101T000000Z-$VERIFY_ENVIRONMENT.abcdef"
VERIFY_OTHER_OLD="$VERIFY_ROOT/.local/backups/20200101T000000Z-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.abcdef"
mkdir "$VERIFY_OWN_OLD" "$VERIFY_OTHER_OLD"
touch -t 202001010000 "$VERIFY_OWN_OLD" "$VERIFY_OTHER_OLD"
sh "$VERIFY_ROOT/scripts/recovery.sh" backup > "$VERIFY_ROOT/backup-result.log" 2>&1
[ ! -e "$VERIFY_OWN_OLD" ] && [ -d "$VERIFY_OTHER_OLD" ] && [ ! -e "$LOCK" ]
printf '%s\n' 'PASS: interrupted second import/main hold/simulator hold retain marker; ordinary startup is blocked; changed dumps and concurrent restore are refused; exact paired resume commits both holds before restart.'
printf '%s\n' 'PASS: startup holds the same action lock across Compose; concurrent restore cannot stop/import; a dead-process action lock can be recovered.'
printf '%s\n' 'PASS: backup uses a unique destination and retention deletes only old backups for its own environment.'
printf '%s\n' 'Scope: mocked Docker command/control flow only. Real database restoration, image timeout availability and broker recovery remain separate acceptance work.'
