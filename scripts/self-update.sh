#!/usr/bin/env bash
#
# Non-interactive updater invoked by the in-app "Update now" button
# (org.booklore.service.system.SystemUpdateService). It is spawned detached
# (`setsid`) so it outlives the API process that the service restart kills.
#
#   self-update.sh [LOCK_FILE]
#
# LOCK_FILE, if given, is removed on exit so the server clears its "update in
# progress" state even if the build fails.
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="${1:-}"

if [ -n "$LOCK_FILE" ]; then
  trap 'rm -f "$LOCK_FILE" 2>/dev/null || true' EXIT
fi

echo "[self-update] $(date -Is) starting in $REPO_DIR"

# deploy.sh does the actual work (fetch tags, pull, rebuild per INSTALL_MODE,
# stamp APP_VERSION, restart the service). --skip-pg-check avoids the one
# postgres-sudo step, which the service account is not granted.
bash "$REPO_DIR/deploy.sh" --skip-pg-check

echo "[self-update] $(date -Is) finished"
