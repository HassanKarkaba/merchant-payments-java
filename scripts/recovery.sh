#!/bin/sh
set -eu
umask 077
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
ENV_FILE=${MPRP_ENV_FILE:-$ROOT/.local/environment.env}
set -a
. "$ENV_FILE"
set +a
[ -z "$(printf '%s' "$MPRP_ENVIRONMENT_ID" | tr -d '0-9a-fA-F-')" ] || { echo 'Invalid environment identity.' >&2; exit 1; }
case "$MPRP_ENVIRONMENT_ID" in ????????-????-????-????-????????????) ;; *) echo 'Invalid environment identity.' >&2; exit 1;; esac
compose(){ docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml" "$@"; }
RESTORE_MARKER="$ENV_FILE.restore-in-progress"
RESTORE_IDENTITY_TEMP=
. "$ROOT/scripts/environment-lock.sh"
bounded_exec(){ RECOVERY_SERVICE=$1; RECOVERY_SECONDS=$2; shift 2; compose exec -T "$RECOVERY_SERVICE" timeout -s TERM -k 5 "$RECOVERY_SECONDS" "$@"; }
has_restore_marker(){ [ -e "$RESTORE_MARKER" ] || [ -L "$RESTORE_MARKER" ]; }
restart_after_backup(){ if has_restore_marker; then echo 'Restore in progress; effect services remain stopped.' >&2; else compose up -d web worker simulator simulator-worker; fi; }
release_restore_lock(){
 if [ -n "${RESTORE_IDENTITY_TEMP:-}" ]; then rm -f "$RESTORE_IDENTITY_TEMP"; fi
 environment_release
}
case "${1:-}" in
 backup)
  trap environment_release EXIT
  environment_acquire
  ! has_restore_marker || { echo 'An interrupted restore must finish before backup or startup.' >&2; exit 1; }
  mkdir -p "$ROOT/.local/backups"
  DEST=$(mktemp -d "$ROOT/.local/backups/$(date -u +%Y%m%dT%H%M%SZ)-$MPRP_ENVIRONMENT_ID.XXXXXX")
  compose stop -t 35 web worker simulator simulator-worker
  trap 'restart_after_backup; environment_release' EXIT
  trap 'exit 130' INT
  trap 'exit 129' HUP
  trap 'exit 143' TERM
  bounded_exec main-postgres 180 pg_dump -U postgres -d mprp -Fc > "$DEST/main.dump"
  bounded_exec simulator-postgres 180 pg_dump -U postgres -d simulator -Fc > "$DEST/simulator.dump"
  bounded_exec kafka 45 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --all-groups --describe > "$DEST/kafka-positions.txt" 2>&1
  bounded_exec kafka 45 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe > "$DEST/kafka-topics.txt" 2>&1
  bounded_exec kafka 45 /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 --entity-type brokers --entity-default --describe > "$DEST/kafka-broker-config.txt" 2>&1
  printf '%s\n' "$KAFKA_CLUSTER_ID" > "$DEST/kafka-cluster-id"
  bounded_exec main-postgres 30 psql -U postgres -d mprp -At -c 'select environment_id,instance_id,run_id,dispatch_epoch,provider_instance_id,provider_cursor,unfinished_jobs,unpublished_events from system_control;' > "$DEST/main-replay-state.txt"
  bounded_exec main-postgres 30 psql -U postgres -d mprp -At -c 'select stream,aggregate_id,max(sequence),count(*) filter(where published_at is null) from publication_outbox group by stream,aggregate_id order by stream,aggregate_id;' > "$DEST/outbox-frontiers.txt"
  bounded_exec simulator-postgres 30 psql -U postgres -d simulator -At -c 'select environment_id,instance_id,main_instance_id,dispatch_epoch,event_sequence,source_sequence,acknowledged_cursor from sim_control;' > "$DEST/simulator-replay-state.txt"
  compose images > "$DEST/images.txt"
  printf '%s\n' "$MPRP_ENVIRONMENT_ID" > "$DEST/environment-id"
  echo "Backup saved to $DEST. Protect encryption keys separately."
  find "$ROOT/.local/backups" -mindepth 1 -maxdepth 1 -type d -name "*-$MPRP_ENVIRONMENT_ID.*" -mtime +30 -exec rm -r -- '{}' \;
  ;;
 restore)
  SOURCE=${2:?Supply backup directory}
  SOURCE=$(CDPATH= cd -- "$SOURCE" && pwd -P)
  [ -f "$SOURCE/environment-id" ] && [ "$(cat "$SOURCE/environment-id")" = "$MPRP_ENVIRONMENT_ID" ] || { echo 'Foreign or incomplete backup.' >&2; exit 1; }
  [ -f "$SOURCE/main.dump" ] && [ -f "$SOURCE/simulator.dump" ] || { echo 'Both paired dumps are required.' >&2; exit 1; }
  printf 'Type exact environment UUID %s to restore only this sandbox: ' "$MPRP_ENVIRONMENT_ID"
  IFS= read -r CONFIRM
  [ "$CONFIRM" = "$MPRP_ENVIRONMENT_ID" ] || { echo 'Restore cancelled.'; exit 1; }
  trap release_restore_lock EXIT
  trap 'exit 130' INT
  trap 'exit 129' HUP
  trap 'exit 143' TERM
  environment_acquire
  RESTORE_IDENTITY_TEMP=$(mktemp "$ENV_FILE.restore-identity.XXXXXX")
  { printf '%s\n%s\n' "$MPRP_ENVIRONMENT_ID" "$SOURCE"; openssl dgst -sha256 -r "$SOURCE/main.dump"; openssl dgst -sha256 -r "$SOURCE/simulator.dump"; } > "$RESTORE_IDENTITY_TEMP"
  if has_restore_marker; then
   [ -f "$RESTORE_MARKER" ] && [ ! -L "$RESTORE_MARKER" ] && cmp -s "$RESTORE_IDENTITY_TEMP" "$RESTORE_MARKER" || { echo 'An interrupted restore belongs to a different or changed backup. Resume its exact retained backup; ordinary startup remains blocked.' >&2; exit 1; }
  else
   ln "$RESTORE_IDENTITY_TEMP" "$RESTORE_MARKER" || { echo 'Restore marker could not be reserved; no database was restored.' >&2; exit 1; }
  fi
  rm "$RESTORE_IDENTITY_TEMP"
  RESTORE_IDENTITY_TEMP=
  compose stop -t 35 web worker simulator simulator-worker
  bounded_exec main-postgres 180 pg_restore -U postgres -d mprp --single-transaction --clean --if-exists --exit-on-error < "$SOURCE/main.dump"
  bounded_exec simulator-postgres 180 pg_restore -U postgres -d simulator --single-transaction --clean --if-exists --exit-on-error < "$SOURCE/simulator.dump"
  bounded_exec main-postgres 30 psql -U postgres -d mprp -v ON_ERROR_STOP=1 -c "update system_control set restore_hold=true,restore_requested=true,dispatch_epoch=dispatch_epoch+1,run_id=gen_random_uuid(),provider_checked_at=null; update durable_job set generation=generation+1,lease_until=null,state='READY' where state='RUNNING';"
  bounded_exec simulator-postgres 30 psql -U postgres -d simulator -v ON_ERROR_STOP=1 -c 'update sim_control set restored=true where id=1;'
  rm "$RESTORE_MARKER"
  compose up -d web worker simulator simulator-worker
  echo 'Restored into recovery hold. Independent inventory and finance release are required.'
  ;;
 *) echo 'Use backup or restore.'; exit 2 ;;
esac
