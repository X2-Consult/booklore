#!/usr/bin/env bash
#
# Moves a native Booklore install (set up by install.sh: systemd services + local PostgreSQL)
# over to the Trove names, keeping the library, users, settings and reading progress:
#
#   checkout        /opt/booklore               -> /opt/trove
#   data            /srv/booklore               -> /srv/trove  (covers, settings, library, BookDrop)
#   settings        /etc/booklore/booklore.env  -> /etc/trove/trove.env
#   services        booklore-api, booklore-ui   -> trove, trove-ui  (drop-ins such as JVM settings too)
#   in-app updates  /etc/sudoers.d/booklore     -> /etc/sudoers.d/trove
#   database        booklore (and role booklore) -> trove (and role trove)
#   stored paths    library and BookDrop paths under the old data folder
#   git remote      X2-Consult/booklore         -> X2-Consult/trove  (host and SSH alias kept)
#
# A folder is only renamed when it's called "booklore"; custom locations stay where they are.
#
# Usage (run from the checkout, as the account the service runs as; it uses sudo where needed):
#   scripts/migrate-to-trove.sh --dry-run    # show what would change, change nothing
#   scripts/migrate-to-trove.sh              # migrate (asks first; add --yes to skip the question)
#   scripts/migrate-to-trove.sh --rollback   # put the Booklore layout back from the last migration
#
# Before changing anything it saves the unit files, drop-ins, env file, sudoers rule and a pg_dump
# of the database under /var/backups/trove-migration/<time>/. If a step fails, the steps already
# done are undone in reverse; if Trove doesn't come up healthy, it offers to roll back.
#
# TROVE_MIGRATE_* variables exist only for scripts/test-migrate-to-trove.sh, which runs the whole
# migration against a throwaway PostgreSQL and a fake root folder.

set -Eeuo pipefail

ROOT="${TROVE_MIGRATE_ROOT:-}"
SUDO="${TROVE_MIGRATE_SUDO-sudo}"
SYSTEMCTL="${TROVE_MIGRATE_SYSTEMCTL:-systemctl}"
JOURNALCTL="${TROVE_MIGRATE_JOURNALCTL:-journalctl}"
VISUDO="${TROVE_MIGRATE_VISUDO:-visudo}"
if [ -n "${TROVE_MIGRATE_PSQL:-}" ]; then read -ra PSQL <<< "$TROVE_MIGRATE_PSQL"; else PSQL=(sudo -u postgres psql); fi
if [ -n "${TROVE_MIGRATE_PG_DUMP:-}" ]; then read -ra PG_DUMP <<< "$TROVE_MIGRATE_PG_DUMP"; else PG_DUMP=(sudo -u postgres pg_dump); fi
HEALTH_TIMEOUT="${TROVE_MIGRATE_HEALTH_TIMEOUT:-300}"

SYSTEMD_DIR="$ROOT/etc/systemd/system"
SUDOERS_DIR="$ROOT/etc/sudoers.d"
BACKUP_BASE="$ROOT/var/backups/trove-migration"
OLD_ENV_FILE="$ROOT/etc/booklore/booklore.env"
NEW_ENV_FILE="$ROOT/etc/trove/trove.env"
OLD_API="booklore-api"; NEW_API="trove"
OLD_UI="booklore-ui";   NEW_UI="trove-ui"
OLD_API_UNIT="$SYSTEMD_DIR/$OLD_API.service"
OLD_UI_UNIT="$SYSTEMD_DIR/$OLD_UI.service"
OLD_SUDOERS="$SUDOERS_DIR/booklore"; NEW_SUDOERS="$SUDOERS_DIR/trove"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MODE="migrate"; ASSUME_YES=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) MODE="dry-run" ;;
    --rollback) MODE="rollback" ;;
    --yes|-y) ASSUME_YES=true ;;
    -h|--help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

log()  { echo ">> $*"; }
warn() { echo "!! $*" >&2; }
die()  { echo "!! $*" >&2; exit 1; }
env_value() { grep -m1 "^$1=" "$2" 2>/dev/null | cut -d= -f2- || true; }
unit_value() { grep -m1 "^$1=" "$2" 2>/dev/null | sed "s|^$1=||" || true; }  # $1 may itself contain "="
sql_literal() { printf "'%s'" "${1//\'/\'\'}"; }
sql_ident() { printf '"%s"' "${1//\"/\"\"}"; }
pg_admin() { (cd / && "${PSQL[@]}" -X -q -At -v ON_ERROR_STOP=1 "$@"); }
renamed() { # $1 = path; prints the path with a final "booklore" component renamed to "trove"
  if [ "$(basename "$1")" = "booklore" ]; then echo "$(dirname "$1")/trove"; else echo "$1"; fi
}
as_app_user() { if [ "$(id -un)" = "$APP_USER" ]; then "$@"; else sudo -u "$APP_USER" "$@"; fi; }

# ─── Work out the current layout ────────────────────────────────────────────
detect_layout() {
  [ -f "$OLD_ENV_FILE" ] || die "No $OLD_ENV_FILE - this doesn't look like a Booklore install made by install.sh."
  [ -f "$OLD_API_UNIT" ] || die "No $OLD_API_UNIT - this doesn't look like a Booklore install made by install.sh."
  HAS_UI=false; [ -f "$OLD_UI_UNIT" ] && HAS_UI=true

  APP_USER="$(unit_value User "$OLD_API_UNIT")"
  local workdir config_dir
  workdir="$(unit_value WorkingDirectory "$OLD_API_UNIT")"
  config_dir="$(unit_value Environment=APP_PATH_CONFIG "$OLD_API_UNIT")"
  [ -n "$APP_USER" ] && [ -n "$workdir" ] && [ -n "$config_dir" ] \
    || die "$OLD_API_UNIT is missing User=, WorkingDirectory= or APP_PATH_CONFIG; migrate it by hand."
  OLD_REPO="${workdir%/booklore-api}"
  OLD_DATA="${config_dir%/config}"
  NEW_REPO="$(renamed "$OLD_REPO")"
  NEW_DATA="$(renamed "$OLD_DATA")"

  DB_URL="$(env_value DATABASE_URL "$OLD_ENV_FILE")"
  OLD_ROLE="$(env_value DATABASE_USERNAME "$OLD_ENV_FILE")"
  DB_PASSWORD="$(env_value DATABASE_PASSWORD "$OLD_ENV_FILE")"
  INSTALL_MODE="$(env_value INSTALL_MODE "$OLD_ENV_FILE")"; INSTALL_MODE="${INSTALL_MODE:-dev}"
  [[ "$DB_URL" =~ ^jdbc:postgresql://([^/:]+)(:([0-9]+))?/([^?]+) ]] \
    || die "DATABASE_URL in $OLD_ENV_FILE isn't a local PostgreSQL URL; this script only handles native PostgreSQL installs."
  DB_HOST="${BASH_REMATCH[1]}"; DB_PORT="${BASH_REMATCH[3]:-5432}"; OLD_DB="${BASH_REMATCH[4]}"
  [ "$OLD_DB" = "booklore" ] && NEW_DB="trove" || NEW_DB="$OLD_DB"
  [ "$OLD_ROLE" = "booklore" ] && NEW_ROLE="trove" || NEW_ROLE="$OLD_ROLE"

  HAS_SUDOERS=false; [ -e "$OLD_SUDOERS" ] && HAS_SUDOERS=true
  APP_PORT="$(env_value TROVE_PORT "$OLD_ENV_FILE")"; APP_PORT="${APP_PORT:-$(env_value BOOKLORE_PORT "$OLD_ENV_FILE")}"
  HEALTH_URL="${TROVE_MIGRATE_HEALTH_URL:-http://localhost:${APP_PORT:-6060}/api/v1/healthcheck}"

  OLD_REMOTE="$(git -C "$OLD_REPO" remote get-url origin 2>/dev/null || true)"
  NEW_REMOTE="$OLD_REMOTE"
  if [[ "$OLD_REMOTE" =~ ^(.*[:/])([Xx]2-[Cc]onsult)/booklore(\.git)?/?$ ]]; then
    NEW_REMOTE="${BASH_REMATCH[1]}${BASH_REMATCH[2]}/trove${BASH_REMATCH[3]}"
  fi
}

# Library and BookDrop paths the database stores under the old data folder.
PATH_COLUMNS=("library_path:path" "bookdrop_file:file_path")

db_read() { # read-only queries with the app's own login, so --dry-run needs no sudo
  (cd / && PGPASSWORD="$DB_PASSWORD" psql -X -q -At -h "$DB_HOST" -p "$DB_PORT" -U "$OLD_ROLE" -d "$OLD_DB" -v ON_ERROR_STOP=1 "$@")
}

count_paths_under() { # $1 = folder; prints "table.column count" for rows that point inside it
  local col table column
  for col in "${PATH_COLUMNS[@]}"; do
    table="${col%%:*}"; column="${col#*:}"
    printf '%s.%s %s\n' "$table" "$column" "$(db_read -c "SELECT count(*) FROM $(sql_ident "$table") WHERE $(sql_ident "$column") = $(sql_literal "$1") OR left($(sql_ident "$column"), $(( ${#1} + 1 ))) = $(sql_literal "$1/")")"
  done
}

check_preconditions() {
  [ "$(id -un)" = "$APP_USER" ] || [ -n "$ROOT" ] \
    || die "Run this as $APP_USER, the account the service runs as (it uses sudo where needed)."
  [ "$REPO_DIR" = "$OLD_REPO" ] || die "Run the copy of this script inside $OLD_REPO (this one is in $REPO_DIR)."
  [ -f "$NEW_ENV_FILE" ] && die "$NEW_ENV_FILE already exists - this machine already has a Trove install."
  [ -e "$SYSTEMD_DIR/$NEW_API.service" ] && die "$SYSTEMD_DIR/$NEW_API.service already exists."
  [ "$NEW_REPO" != "$OLD_REPO" ] && [ -e "$NEW_REPO" ] && die "$NEW_REPO already exists."
  [ "$NEW_DATA" != "$OLD_DATA" ] && [ -e "$NEW_DATA" ] && die "$NEW_DATA already exists."
  grep -q 'API_SERVICE="trove"' "$OLD_REPO/deploy.sh" \
    || die "This checkout predates Trove's deploy.sh. Run ./deploy.sh (git pull + rebuild) first, then migrate."
  if [ "$INSTALL_MODE" = "production" ]; then
    local jar
    jar="$(ls -t "$OLD_REPO"/booklore-api/build/libs/booklore-api-*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -1 || true)"
    [ -n "$jar" ] && grep -q -a "org/booklore/util/EnvVars.class" "$jar" \
      || die "The built app predates Trove. Run ./deploy.sh first so the jar is rebuilt, then migrate."
  fi
  db_read -c "SELECT 1" >/dev/null || die "Can't log in to database $OLD_DB as $OLD_ROLE with the password in $OLD_ENV_FILE."
  if [ "$NEW_DB" != "$OLD_DB" ] && [ "$(db_read -c "SELECT count(*) FROM pg_database WHERE datname = $(sql_literal "$NEW_DB")")" != 0 ]; then
    die "A database called $NEW_DB already exists."
  fi
  if [ "$NEW_ROLE" != "$OLD_ROLE" ] && [ "$(db_read -c "SELECT count(*) FROM pg_roles WHERE rolname = $(sql_literal "$NEW_ROLE")")" != 0 ]; then
    die "A database role called $NEW_ROLE already exists."
  fi
}

print_plan() {
  echo
  echo "Booklore install found (${INSTALL_MODE} mode, runs as ${APP_USER}). The migration will:"
  echo
  echo "  Stop        $OLD_API$($HAS_UI && echo ", $OLD_UI")"
  echo "  Back up     unit files, env file, sudoers rule and a pg_dump of '$OLD_DB' to $BACKUP_BASE/<time>/"
  [ "$OLD_DB" != "$NEW_DB" ]     && echo "  Database    rename '$OLD_DB' -> '$NEW_DB'"
  [ "$OLD_ROLE" != "$NEW_ROLE" ] && echo "  DB login    rename role '$OLD_ROLE' -> '$NEW_ROLE' (same password)"
  if [ "$OLD_DATA" != "$NEW_DATA" ]; then
    echo "  Data        move $OLD_DATA -> $NEW_DATA"
    local line; while read -r line; do
      [ "${line##* }" != 0 ] && echo "              rewrite ${line##* } stored path(s) in ${line% *}"
    done < <(count_paths_under "$OLD_DATA")
    if [ "$(stat -c %d "$(dirname "$OLD_DATA")")" != "$(stat -c %d "$OLD_DATA")" ]; then
      echo "              ($OLD_DATA is a separate mount: it will be copied, which can take a while)"
    fi
  else
    echo "  Data        stays in $OLD_DATA"
  fi
  [ "$OLD_REPO" != "$NEW_REPO" ] && echo "  Checkout    move $OLD_REPO -> $NEW_REPO"
  echo "  Settings    $OLD_ENV_FILE -> $NEW_ENV_FILE"
  echo "  Services    $OLD_API -> $NEW_API$($HAS_UI && echo ", $OLD_UI -> $NEW_UI")$([ -d "$SYSTEMD_DIR/$OLD_API.service.d" ] && echo " (with drop-ins: $(ls "$SYSTEMD_DIR/$OLD_API.service.d" | tr '\n' ' '))")"
  $HAS_SUDOERS && echo "  Updates     $OLD_SUDOERS -> $NEW_SUDOERS"
  [ "$OLD_REMOTE" != "$NEW_REMOTE" ] && echo "  Git remote  $OLD_REMOTE -> $NEW_REMOTE"
  echo "  Start       $NEW_API$($HAS_UI && echo ", $NEW_UI") and wait for $HEALTH_URL"
  echo
  local others
  others="$( { grep -l -s -i "booklore" "$ROOT/etc/caddy/Caddyfile" "$(getent passwd "$APP_USER" | cut -d: -f6)/Caddyfile" \
    "$ROOT"/etc/nginx/sites-enabled/* "$ROOT"/etc/cron.d/* 2>/dev/null || true; } | tr '\n' ' ')"
  [ -n "$others" ] && echo "  Not changed, but mentions booklore - check by hand: $others" && echo
  local audit
  audit="$(db_read -c "SELECT count(*) FROM audit_log WHERE description LIKE $(sql_literal "%$OLD_DATA%")" 2>/dev/null || echo 0)"
  [ "$audit" != 0 ] && echo "  Audit log entries that mention $OLD_DATA stay as written (they're history)." && echo
  return 0
}

# ─── Steps. Each has an undo; completed steps are recorded so they can be reversed. ──
record() { echo "$1" | $SUDO tee -a "$BACKUP_DIR/steps" >/dev/null; }

step_stop() {
  log "Stopping $OLD_API$($HAS_UI && echo " and $OLD_UI")..."
  $SUDO $SYSTEMCTL stop "$OLD_API"; $HAS_UI && $SUDO $SYSTEMCTL stop "$OLD_UI"; record stop
}
undo_stop() { $SUDO $SYSTEMCTL start "$OLD_API"; if $HAS_UI; then $SUDO $SYSTEMCTL start "$OLD_UI"; fi; }

step_backup() {
  log "Backing up to $BACKUP_DIR..."
  $SUDO cp -a "$OLD_API_UNIT" "$BACKUP_DIR/"
  $HAS_UI && $SUDO cp -a "$OLD_UI_UNIT" "$BACKUP_DIR/"
  [ -d "$SYSTEMD_DIR/$OLD_API.service.d" ] && $SUDO cp -a "$SYSTEMD_DIR/$OLD_API.service.d" "$BACKUP_DIR/"
  $HAS_UI && [ -d "$SYSTEMD_DIR/$OLD_UI.service.d" ] && $SUDO cp -a "$SYSTEMD_DIR/$OLD_UI.service.d" "$BACKUP_DIR/"
  $SUDO cp -a "$OLD_ENV_FILE" "$BACKUP_DIR/"
  $HAS_SUDOERS && $SUDO cp -a "$OLD_SUDOERS" "$BACKUP_DIR/sudoers-booklore"
  log "Dumping database $OLD_DB (restore with pg_restore if ever needed)..."
  (cd / && "${PG_DUMP[@]}" -Fc "$OLD_DB") | $SUDO tee "$BACKUP_DIR/$OLD_DB.dump" >/dev/null
  $SUDO chmod 600 "$BACKUP_DIR/$OLD_DB.dump" "$BACKUP_DIR/$(basename "$OLD_ENV_FILE")"
  record backup
}
undo_backup() { :; }  # the backup is kept

step_db() {
  local active
  active="$(pg_admin -d postgres -c "SELECT count(*) FROM pg_stat_activity WHERE datname = $(sql_literal "$OLD_DB") AND pid <> pg_backend_pid()")"
  if [ "$active" != 0 ]; then
    pg_admin -d postgres -c "SELECT usename || ' from ' || coalesce(client_addr::text, 'local') || ' (' || coalesce(application_name, '') || ')' FROM pg_stat_activity WHERE datname = $(sql_literal "$OLD_DB") AND pid <> pg_backend_pid()" >&2
    die "Something else is still connected to database $OLD_DB (above). Close it and run the migration again."
  fi
  if [ "$OLD_DB" != "$NEW_DB" ]; then
    log "Renaming database $OLD_DB -> $NEW_DB..."
    pg_admin -d postgres -c "ALTER DATABASE $(sql_ident "$OLD_DB") RENAME TO $(sql_ident "$NEW_DB")"
    record db_name
  fi
  if [ "$OLD_ROLE" != "$NEW_ROLE" ]; then
    log "Renaming database role $OLD_ROLE -> $NEW_ROLE..."
    # Renaming clears an MD5-hashed password, so it's set again from the env file either way.
    pg_admin -d postgres -v pw="$DB_PASSWORD" <<SQL
ALTER ROLE $(sql_ident "$OLD_ROLE") RENAME TO $(sql_ident "$NEW_ROLE");
ALTER ROLE $(sql_ident "$NEW_ROLE") PASSWORD :'pw';
SQL
    record db_role
  fi
}
undo_db_name() { pg_admin -d postgres -c "ALTER DATABASE $(sql_ident "$NEW_DB") RENAME TO $(sql_ident "$OLD_DB")"; }
undo_db_role() {
  pg_admin -d postgres -v pw="$DB_PASSWORD" <<SQL
ALTER ROLE $(sql_ident "$NEW_ROLE") RENAME TO $(sql_ident "$OLD_ROLE");
ALTER ROLE $(sql_ident "$OLD_ROLE") PASSWORD :'pw';
SQL
}

rewrite_paths() { # $1 = database, $2 = from folder, $3 = to folder
  local col table column
  for col in "${PATH_COLUMNS[@]}"; do
    table="${col%%:*}"; column="${col#*:}"
    pg_admin -d "$1" -c "UPDATE $(sql_ident "$table") SET $(sql_ident "$column") = $(sql_literal "$3") || substr($(sql_ident "$column"), $(( ${#2} + 1 ))) WHERE $(sql_ident "$column") = $(sql_literal "$2") OR left($(sql_ident "$column"), $(( ${#2} + 1 ))) = $(sql_literal "$2/")" >/dev/null
  done
}
step_db_paths() {
  [ "$OLD_DATA" = "$NEW_DATA" ] && return 0
  log "Rewriting stored paths under $OLD_DATA to $NEW_DATA..."
  rewrite_paths "$NEW_DB" "$OLD_DATA" "$NEW_DATA"; record db_paths
}
undo_db_paths() { rewrite_paths "$NEW_DB" "$NEW_DATA" "$OLD_DATA"; }

step_move_data() {
  [ "$OLD_DATA" = "$NEW_DATA" ] && return 0
  log "Moving $OLD_DATA -> $NEW_DATA..."
  $SUDO mv "$OLD_DATA" "$NEW_DATA"; record move_data
}
undo_move_data() { $SUDO mv "$NEW_DATA" "$OLD_DATA"; }

step_move_repo() {
  [ "$OLD_REPO" = "$NEW_REPO" ] && return 0
  log "Moving $OLD_REPO -> $NEW_REPO..."
  $SUDO mv "$OLD_REPO" "$NEW_REPO"; record move_repo
}
undo_move_repo() { $SUDO mv "$NEW_REPO" "$OLD_REPO"; }

rewrite_text() { # stdin -> stdout, with every old location replaced by its new one
  local text; text="$(cat)"
  text="${text//"$OLD_ENV_FILE"/"$NEW_ENV_FILE"}"
  [ "$OLD_REPO" != "$NEW_REPO" ] && text="${text//"$OLD_REPO"/"$NEW_REPO"}"
  [ "$OLD_DATA" != "$NEW_DATA" ] && text="${text//"$OLD_DATA"/"$NEW_DATA"}"
  printf '%s\n' "$text"
}

step_env() {
  log "Writing $NEW_ENV_FILE..."
  local tmp; tmp="$(mktemp)"
  # shellcheck disable=SC2002
  cat "$OLD_ENV_FILE" | rewrite_text | awk -v old_db="$OLD_DB" -v new_db="$NEW_DB" -v old_role="$OLD_ROLE" -v new_role="$NEW_ROLE" -v repo="$NEW_REPO" '
    /^DATABASE_URL=/ { sub("/" old_db "$", "/" new_db); sub("/" old_db "\\?", "/" new_db "?") }
    /^DATABASE_USERNAME=/ && $0 == "DATABASE_USERNAME=" old_role { $0 = "DATABASE_USERNAME=" new_role }
    /^(BOOKLORE|TROVE)_REPO_DIR=/ { next }
    /^BOOKLORE_[A-Z_]*=/ { sub(/^BOOKLORE_/, "TROVE_") }
    { print }
    END { print "TROVE_REPO_DIR=" repo }' > "$tmp"
  $SUDO install -d -m 755 "$(dirname "$NEW_ENV_FILE")"
  $SUDO install -o "$APP_USER" -m 600 "$tmp" "$NEW_ENV_FILE"
  rm -f "$tmp"
  $SUDO rm -f "$OLD_ENV_FILE"
  $SUDO rmdir "$(dirname "$OLD_ENV_FILE")" 2>/dev/null || true
  record env
}
undo_env() {
  $SUDO install -d -m 755 "$(dirname "$OLD_ENV_FILE")"
  $SUDO install -o "$APP_USER" -m 600 "$BACKUP_DIR/$(basename "$OLD_ENV_FILE")" "$OLD_ENV_FILE"
  $SUDO rm -f "$NEW_ENV_FILE"; $SUDO rmdir "$(dirname "$NEW_ENV_FILE")" 2>/dev/null || true
}

move_unit() { # $1 = old service name, $2 = new service name
  local old="$SYSTEMD_DIR/$1.service" new="$SYSTEMD_DIR/$2.service" f
  $SUDO cat "$old" | rewrite_text | sed 's/^Description=BookLore/Description=Trove/' | $SUDO tee "$new" >/dev/null
  if [ -d "$old.d" ]; then
    $SUDO install -d -m 755 "$new.d"
    for f in "$old.d"/*; do
      [ -f "$f" ] || continue
      $SUDO cat "$f" | rewrite_text | $SUDO tee "$new.d/$(basename "$f")" >/dev/null
    done
  fi
  $SUDO $SYSTEMCTL disable "$1" >/dev/null 2>&1 || true
  $SUDO rm -rf "$old" "$old.d"
}
step_units() {
  log "Replacing services: $OLD_API -> $NEW_API$($HAS_UI && echo ", $OLD_UI -> $NEW_UI")..."
  record units   # recorded first: undo copes with a half-done swap
  move_unit "$OLD_API" "$NEW_API"
  $HAS_UI && move_unit "$OLD_UI" "$NEW_UI"
  $SUDO $SYSTEMCTL daemon-reload
  $SUDO $SYSTEMCTL enable "$NEW_API" >/dev/null 2>&1
  $HAS_UI && $SUDO $SYSTEMCTL enable "$NEW_UI" >/dev/null 2>&1
  return 0
}
undo_units() {
  local name
  for name in "$NEW_API" "$NEW_UI"; do
    $SUDO $SYSTEMCTL disable "$name" >/dev/null 2>&1 || true
    $SUDO rm -rf "$SYSTEMD_DIR/$name.service" "$SYSTEMD_DIR/$name.service.d"
  done
  $SUDO cp -a "$BACKUP_DIR/$OLD_API.service" "$SYSTEMD_DIR/"
  [ -d "$BACKUP_DIR/$OLD_API.service.d" ] && $SUDO cp -a "$BACKUP_DIR/$OLD_API.service.d" "$SYSTEMD_DIR/"
  if $HAS_UI; then
    $SUDO cp -a "$BACKUP_DIR/$OLD_UI.service" "$SYSTEMD_DIR/"
    [ -d "$BACKUP_DIR/$OLD_UI.service.d" ] && $SUDO cp -a "$BACKUP_DIR/$OLD_UI.service.d" "$SYSTEMD_DIR/"
  fi
  $SUDO $SYSTEMCTL daemon-reload
  $SUDO $SYSTEMCTL enable "$OLD_API" >/dev/null 2>&1 || true
  if $HAS_UI; then $SUDO $SYSTEMCTL enable "$OLD_UI" >/dev/null 2>&1 || true; fi
}

step_sudoers() {
  $HAS_SUDOERS || return 0
  log "Updating the in-app update rule: $NEW_SUDOERS..."
  local tmp; tmp="$(mktemp)"
  $SUDO cat "$OLD_SUDOERS" | sed "s/ restart $OLD_API\b/ restart $NEW_API/g; s/ restart $OLD_UI\b/ restart $NEW_UI/g" > "$tmp"
  $SUDO $VISUDO -cf "$tmp" >/dev/null || { rm -f "$tmp"; die "The rewritten sudoers rule doesn't validate."; }
  $SUDO install -m 0440 "$tmp" "$NEW_SUDOERS"; rm -f "$tmp"
  $SUDO rm -f "$OLD_SUDOERS"
  record sudoers
}
undo_sudoers() { $SUDO install -m 0440 "$BACKUP_DIR/sudoers-booklore" "$OLD_SUDOERS"; $SUDO rm -f "$NEW_SUDOERS"; }

step_git() {
  [ "$OLD_REMOTE" = "$NEW_REMOTE" ] && return 0
  log "Pointing the git remote at $NEW_REMOTE..."
  as_app_user git -C "$NEW_REPO" remote set-url origin "$NEW_REMOTE"; record git
}
undo_git() { as_app_user git -C "$NEW_REPO" remote set-url origin "$OLD_REMOTE"; }

wait_healthy() {
  local waited=0
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    curl -fs "$HEALTH_URL" >/dev/null 2>&1 && return 0
    sleep 5; waited=$((waited + 5))
  done
  return 1
}
step_start() {
  log "Starting $NEW_API$($HAS_UI && echo " and $NEW_UI")..."
  record start
  $SUDO $SYSTEMCTL start "$NEW_API" || return 1
  if $HAS_UI; then $SUDO $SYSTEMCTL start "$NEW_UI" || return 1; fi
  log "Waiting up to ${HEALTH_TIMEOUT}s for $HEALTH_URL..."
  wait_healthy
}
undo_start() { $SUDO $SYSTEMCTL stop "$NEW_API" 2>/dev/null || true; if $HAS_UI; then $SUDO $SYSTEMCTL stop "$NEW_UI" 2>/dev/null || true; fi; }

# ─── Rollback ───────────────────────────────────────────────────────────────
save_manifest() {
  local var
  for var in APP_USER HAS_UI HAS_SUDOERS OLD_REPO NEW_REPO OLD_DATA NEW_DATA OLD_DB NEW_DB OLD_ROLE NEW_ROLE \
             DB_HOST DB_PORT OLD_REMOTE NEW_REMOTE HEALTH_URL INSTALL_MODE; do
    printf '%s=%q\n' "$var" "${!var}"
  done | $SUDO tee "$BACKUP_DIR/manifest" >/dev/null
}

undo_steps() { # reverse every recorded step, carrying on past failures
  local steps step failed=0
  mapfile -t steps < <($SUDO cat "$BACKUP_DIR/steps" 2>/dev/null || true)
  set +e
  for (( i=${#steps[@]}-1; i>=0; i-- )); do
    step="${steps[i]}"
    log "Undoing: $step"
    if ! "undo_$step"; then warn "Undoing '$step' failed - see $BACKUP_DIR for the saved files."; failed=1; fi
  done
  set -e
  $SUDO sed -i 's/^/undone:/' "$BACKUP_DIR/steps" 2>/dev/null || true
  return $failed
}

rollback_last() {
  local latest
  latest="$($SUDO ls -1d "$BACKUP_BASE"/*/ 2>/dev/null | sort | tail -1 || true)"
  [ -n "$latest" ] || die "No migration backups in $BACKUP_BASE - nothing to roll back."
  BACKUP_DIR="${latest%/}"
  $SUDO grep -q . "$BACKUP_DIR/steps" 2>/dev/null && ! $SUDO grep -q '^undone:' "$BACKUP_DIR/steps" \
    || die "The last migration ($BACKUP_DIR) has already been rolled back."
  eval "$($SUDO cat "$BACKUP_DIR/manifest")"
  DB_PASSWORD="$($SUDO cat "$BACKUP_DIR/$(basename "$OLD_ENV_FILE")" | grep -m1 '^DATABASE_PASSWORD=' | cut -d= -f2-)"
  echo "Rolling back the migration in $BACKUP_DIR: $NEW_REPO -> $OLD_REPO, database $NEW_DB -> $OLD_DB, services back to $OLD_API."
  if ! $ASSUME_YES; then read -rp "Continue? [y/N] " answer; [[ "$answer" =~ ^[Yy] ]] || exit 1; fi
  undo_start
  if undo_steps; then
    log "Rolled back. Booklore runs from $OLD_REPO again (cd $OLD_REPO)."
  else
    die "Rollback finished with errors (above). The saved files are in $BACKUP_DIR."
  fi
}

# ─── Main ───────────────────────────────────────────────────────────────────
if [ "$MODE" = "rollback" ]; then rollback_last; exit 0; fi

detect_layout
check_preconditions
print_plan
if [ "$MODE" = "dry-run" ]; then echo "Dry run: nothing was changed."; exit 0; fi
if ! $ASSUME_YES; then read -rp "Migrate now? The app is offline until it restarts. [y/N] " answer; [[ "$answer" =~ ^[Yy] ]] || exit 1; fi

BACKUP_DIR="$BACKUP_BASE/$(date +%Y%m%d-%H%M%S)"
$SUDO install -d -m 700 "$BACKUP_DIR"
save_manifest

# Any failure or interruption from here on (a failed command, die, Ctrl-C) undoes the steps done so far.
MIGRATING=true
on_exit() {
  local status=$?
  if [ "$status" != 0 ] && $MIGRATING; then
    MIGRATING=false
    warn "The migration stopped (exit $status); undoing the steps already done..."
    if undo_steps; then warn "Put back as it was. Nothing is left half-migrated."
    else warn "Some steps couldn't be undone; the saved files are in $BACKUP_DIR."; fi
  fi
}
trap on_exit EXIT
trap 'exit 130' INT TERM

step_stop
step_backup
step_db
step_db_paths
step_move_data
step_move_repo
step_env
step_units
step_sudoers
step_git
if ! step_start; then
  MIGRATING=false
  warn "Trove didn't answer $HEALTH_URL within ${HEALTH_TIMEOUT}s. Last log lines:"
  $SUDO $JOURNALCTL -u "$NEW_API" -n 30 --no-pager >&2 || true
  answer="y"
  [ -t 0 ] && read -rp "Roll back to Booklore now? [Y/n] " answer
  if [[ ! "$answer" =~ ^[Nn] ]]; then
    undo_steps && log "Rolled back; Booklore is running as before." || warn "Some steps couldn't be undone; see $BACKUP_DIR."
    exit 1
  fi
  warn "Left as Trove. Roll back later with: $NEW_REPO/scripts/migrate-to-trove.sh --rollback"
  exit 1
fi
MIGRATING=false

echo
log "Done. Trove is running as $NEW_API from $NEW_REPO."
[ "$OLD_REPO" != "$NEW_REPO" ] && log "Your shell may still be in the old folder: cd $NEW_REPO"
log "Status: systemctl status $NEW_API    Logs: journalctl -u $NEW_API -f    Updates: $NEW_REPO/deploy.sh"
log "Backup: $BACKUP_DIR (roll back with: $NEW_REPO/scripts/migrate-to-trove.sh --rollback)"
