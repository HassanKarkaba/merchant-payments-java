# Sourced by local lifecycle commands. The lock covers marker checks and effect startup together.
ENV_ACTION_LOCK="$ENV_FILE.action-lock"
ENV_ACTION_REAPER="$ENV_FILE.action-lock-reaper"
ENV_ACTION_OWNED=false
ENV_REAPER_OWNED=false
environment_release_reaper(){
 if [ "$ENV_REAPER_OWNED" = true ]; then rm -f "$ENV_ACTION_REAPER/pid"; rmdir "$ENV_ACTION_REAPER"; ENV_REAPER_OWNED=false; fi
}
environment_release(){
 if [ "$ENV_ACTION_OWNED" = true ]; then
  [ "$(cat "$ENV_ACTION_LOCK/pid")" = "$$" ] || { echo 'Environment action lock identity changed; keep services stopped.' >&2; return 1; }
  rm "$ENV_ACTION_LOCK/pid"
  rmdir "$ENV_ACTION_LOCK"
  ENV_ACTION_OWNED=false
 fi
 environment_release_reaper
}
environment_lock_pid(){
 [ -d "$ENV_ACTION_LOCK" ] && [ ! -L "$ENV_ACTION_LOCK" ] && [ -f "$ENV_ACTION_LOCK/pid" ] && [ ! -L "$ENV_ACTION_LOCK/pid" ] || { echo 'Environment action lock is incomplete; keep services stopped and inspect its private state.' >&2; return 1; }
 ENV_PREVIOUS_PID=$(cat "$ENV_ACTION_LOCK/pid")
 case "$ENV_PREVIOUS_PID" in ''|*[!0-9]*) echo 'Invalid environment action lock; keep services stopped.' >&2; return 1;; esac
}
environment_acquire(){
 if ! mkdir "$ENV_ACTION_LOCK" 2>/dev/null; then
  environment_lock_pid || return 1
  if kill -0 "$ENV_PREVIOUS_PID" 2>/dev/null; then echo 'Another environment action is still running.' >&2; return 1; fi
  # Only one dead-process reaper may remove/recreate the lock; it rechecks under this mutex.
  mkdir "$ENV_ACTION_REAPER" 2>/dev/null || { echo 'Environment lock recovery is already in progress; keep services stopped.' >&2; return 1; }
  ENV_REAPER_OWNED=true
  printf '%s\n' "$$" > "$ENV_ACTION_REAPER/pid"
  environment_lock_pid || { environment_release_reaper; return 1; }
  if kill -0 "$ENV_PREVIOUS_PID" 2>/dev/null; then environment_release_reaper; echo 'Another environment action acquired the lock.' >&2; return 1; fi
  rm "$ENV_ACTION_LOCK/pid"
  rmdir "$ENV_ACTION_LOCK"
  mkdir "$ENV_ACTION_LOCK" || { environment_release_reaper; echo 'Another environment action acquired the lock.' >&2; return 1; }
 fi
 ENV_ACTION_OWNED=true
 printf '%s\n' "$$" > "$ENV_ACTION_LOCK/pid"
 environment_release_reaper
}
