#!/usr/bin/env bash
#
# Apply code changes to the native (non-Docker) local BookLore install set up
# by install.sh: pulls latest code, reinstalls frontend deps if needed, and
# restarts the app.
#
# In production mode (single jar, embeds the frontend): rebuilds the Angular
# app and the backend jar, then restarts booklore-api only.
# In dev mode: a fresh gradlew bootRun recompiles the backend and lets Flyway
# apply any new migrations against Postgres on boot, so restarting
# booklore-api / booklore-ui is enough - no separate build step needed.
#
# Usage:
#   ./deploy.sh              # git pull, then restart
#   ./deploy.sh --skip-pull  # restart only, e.g. to deploy uncommitted edits
#

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="/etc/booklore/booklore.env"
SKIP_PULL=false
[ "${1:-}" = "--skip-pull" ] && SKIP_PULL=true

log() { echo ">> $*"; }

# pg_stat_statements is the standard tool for finding slow/expensive queries in Postgres,
# but enabling it needs two steps: adding it to shared_preload_libraries (which only takes
# effect after a Postgres restart) and CREATE EXTENSION in the target database. Idempotent -
# on repeat deploys this is just a cheap SHOW + no-op CREATE EXTENSION IF NOT EXISTS; Postgres
# is only restarted the one time it's actually missing.
ensure_pg_stat_statements() {
  log "Checking pg_stat_statements..."

  local db_url db_name
  db_url="$(grep -oP '(?<=^DATABASE_URL=jdbc:postgresql://).*' "$ENV_FILE" 2>/dev/null || true)"
  db_name="${db_url##*/}"
  db_name="${db_name:-booklore}"

  local preloaded
  preloaded="$(sudo -u postgres psql -tAc "SHOW shared_preload_libraries;" 2>/dev/null || true)"

  if ! echo "$preloaded" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -qx "pg_stat_statements"; then
    log "pg_stat_statements not preloaded - adding it and restarting Postgres..."
    local merged
    if [ -z "$preloaded" ]; then
      merged="pg_stat_statements"
    else
      merged="${preloaded}, pg_stat_statements"
    fi
    sudo -u postgres psql -c "ALTER SYSTEM SET shared_preload_libraries = '${merged}';" >/dev/null
    sudo systemctl restart postgresql

    log "Waiting for Postgres to come back up..."
    for _ in $(seq 1 30); do
      sudo -u postgres psql -tAc "SELECT 1" >/dev/null 2>&1 && break
      sleep 1
    done
  fi

  sudo -u postgres psql -d "$db_name" -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" >/dev/null
  log "pg_stat_statements is enabled on database '${db_name}'."
}

# Resolve JAVA_HOME for the production build step below (gradlew bootJar).
# The interactive shell running this script may not have SDKMAN's env
# sourced (e.g. non-login shells, or it was only ever set up for the
# systemd unit's own Environment= line during install.sh).
resolve_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    return
  fi
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    export JAVA_HOME
    return
  fi
  if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    set +u
    # shellcheck disable=SC1090,SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    set -u
  fi
  if [ -d "$HOME/.sdkman/candidates/java/current" ]; then
    JAVA_HOME="$HOME/.sdkman/candidates/java/current"
    export JAVA_HOME
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
}
resolve_java_home

cd "$REPO_DIR"

INSTALL_MODE="$(grep -oP '(?<=^INSTALL_MODE=).*' "$ENV_FILE" 2>/dev/null || true)"
INSTALL_MODE="${INSTALL_MODE:-dev}"
log "Install mode: $INSTALL_MODE"

ensure_pg_stat_statements

LOCK_CHANGED=false
if [ "$SKIP_PULL" = false ]; then
  log "Pulling latest changes..."
  BEFORE_LOCK="$(git rev-parse HEAD:booklore-ui/package-lock.json 2>/dev/null || true)"
  git pull --ff-only
  AFTER_LOCK="$(git rev-parse HEAD:booklore-ui/package-lock.json 2>/dev/null || true)"
  [ "$BEFORE_LOCK" != "$AFTER_LOCK" ] && LOCK_CHANGED=true
else
  log "Skipping git pull (--skip-pull)."
  git diff --quiet HEAD -- booklore-ui/package-lock.json || LOCK_CHANGED=true
fi

if [ "$LOCK_CHANGED" = true ]; then
  log "Frontend dependencies changed, running npm install..."
  (cd "$REPO_DIR/booklore-ui" && npm install)
else
  log "No frontend dependency changes, skipping npm install."
fi

if [ "$INSTALL_MODE" = "production" ]; then
  log "Building Angular app for production..."
  (cd "$REPO_DIR/booklore-ui" && npx ng build --configuration production)

  log "Building backend jar (embeds the Angular build)..."
  (cd "$REPO_DIR/booklore-api" && ./gradlew bootJar -x test)

  log "Restarting booklore-api..."
  sudo systemctl restart booklore-api
else
  log "Restarting services..."
  sudo systemctl restart booklore-api booklore-ui
fi

log "Waiting for backend to come up..."
for _ in $(seq 1 30); do
  if curl -fs http://localhost:6060/api/v1/healthcheck > /dev/null 2>&1; then
    log "Backend healthy."
    break
  fi
  sleep 2
done

echo
echo "--- booklore-api (last 20 lines) ---"
journalctl -u booklore-api -n 20 --no-pager
if [ "$INSTALL_MODE" != "production" ]; then
  echo
  echo "--- booklore-ui (last 10 lines) ---"
  journalctl -u booklore-ui -n 10 --no-pager
fi
echo
if [ "$INSTALL_MODE" = "production" ]; then
  log "Done. App: http://localhost:6060"
else
  log "Done. Frontend: http://localhost:4200  Backend: http://localhost:6060"
fi
