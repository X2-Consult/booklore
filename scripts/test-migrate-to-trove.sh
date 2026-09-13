#!/usr/bin/env bash
#
# Runs scripts/migrate-to-trove.sh end to end without root: a throwaway PostgreSQL cluster owned by
# the current user, a fake root folder holding a Booklore layout (checkout, data, env file, units
# with a JVM drop-in, sudoers rule), and stand-ins for systemctl/visudo/journalctl. It checks the
# migration, a rollback, and the automatic undo when a step fails.
#
#   scripts/test-migrate-to-trove.sh
#
# Needs PostgreSQL's server binaries (initdb, pg_ctl), git and python3.

set -Eeuo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_BIN="${PG_BIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
WORK="$(mktemp -d)"
ROOT="$WORK/root"
PGDATA="$WORK/pg"; PGSOCK="$WORK/sock"; PGPORT=55439
HEALTH_PORT=55440
PASS=0; FAIL=0

cleanup() {
  "$PG_BIN/pg_ctl" -D "$PGDATA" -m immediate stop >/dev/null 2>&1 || true
  [ -n "${HEALTH_PID:-}" ] && kill "$HEALTH_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

ok()   { echo "  ok   $*"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL $*"; FAIL=$((FAIL + 1)); }
check() { local desc="$1"; shift; if "$@"; then ok "$desc"; else bad "$desc"; fi; }
psql_su() { "$PG_BIN/psql" -X -q -At -h "$PGSOCK" -p "$PGPORT" -U postgres "$@"; }
can_login() { # $1 = role and database, over TCP with the password from the env file
  PGPASSWORD="pw'with\"quotes" "$PG_BIN/psql" -X -At -h localhost -p "$PGPORT" -U "$1" -d "$1" -c "SELECT 1" >/dev/null 2>&1
}

# ─── Throwaway PostgreSQL with a Booklore database ──────────────────────────
mkdir -p "$PGSOCK"
"$PG_BIN/initdb" -D "$PGDATA" -U postgres --auth-local=trust --auth-host=scram-sha-256 >/dev/null
"$PG_BIN/pg_ctl" -D "$PGDATA" -o "-p $PGPORT -k $PGSOCK -c listen_addresses=localhost" -l "$WORK/pg.log" start >/dev/null
psql_su -d postgres -c "CREATE ROLE booklore LOGIN PASSWORD 'pw''with\"quotes'" -c "CREATE DATABASE booklore OWNER booklore"
psql_su -d booklore <<'SQL'
SET ROLE booklore;
CREATE TABLE library_path (id serial PRIMARY KEY, path text);
CREATE TABLE bookdrop_file (id serial PRIMARY KEY, file_path text NOT NULL);
CREATE TABLE audit_log (id serial PRIMARY KEY, description text);
SQL

# ─── A Booklore layout under the fake root ──────────────────────────────────
make_layout() {
  rm -rf "$ROOT"; mkdir -p "$ROOT"/{etc/booklore,etc/systemd/system,etc/sudoers.d,opt,srv/booklore/{config,library,bookdrop}}
  git clone -q "$REPO_DIR" "$ROOT/opt/booklore"
  # the migration script under test, not the committed one
  cp "$REPO_DIR/deploy.sh" "$ROOT/opt/booklore/"
  cp "$REPO_DIR/scripts/migrate-to-trove.sh" "$ROOT/opt/booklore/scripts/"
  git -C "$ROOT/opt/booklore" remote set-url origin "git@github-booklore:X2-Consult/booklore.git"
  echo "cover" > "$ROOT/srv/booklore/config/cover.jpg"
  cat > "$ROOT/etc/booklore/booklore.env" <<EOF
DATABASE_URL=jdbc:postgresql://localhost:$PGPORT/booklore
DATABASE_USERNAME=booklore
DATABASE_PASSWORD=pw'with"quotes
ALLOWED_ORIGINS=http://localhost:4200
INSTALL_MODE=dev
BOOKLORE_RAR_BIN=/usr/local/bin/rar
EOF
  cat > "$ROOT/etc/systemd/system/booklore-api.service" <<EOF
[Unit]
Description=BookLore backend (dev, gradlew bootRun)

[Service]
User=$(id -un)
WorkingDirectory=$ROOT/opt/booklore/booklore-api
EnvironmentFile=$ROOT/etc/booklore/booklore.env
Environment=APP_PATH_CONFIG=$ROOT/srv/booklore/config
Environment=APP_BOOKDROP_FOLDER=$ROOT/srv/booklore/bookdrop
ExecStart=$ROOT/opt/booklore/booklore-api/gradlew bootRun
EOF
  cat > "$ROOT/etc/systemd/system/booklore-ui.service" <<EOF
[Unit]
Description=BookLore frontend (dev, ng serve)

[Service]
User=$(id -un)
WorkingDirectory=$ROOT/opt/booklore/booklore-ui
EOF
  mkdir -p "$ROOT/etc/systemd/system/booklore-api.service.d"
  echo '[Service]
Environment="JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=25.0"' > "$ROOT/etc/systemd/system/booklore-api.service.d/jvm-memory.conf"
  echo "$(id -un) ALL=(root) NOPASSWD: /usr/bin/systemctl restart booklore-api, /usr/bin/systemctl restart booklore-ui" > "$ROOT/etc/sudoers.d/booklore"
  psql_su -d booklore -c "TRUNCATE library_path, bookdrop_file, audit_log" \
    -c "INSERT INTO library_path (path) VALUES ('$ROOT/srv/booklore/library'), ('/mnt/elsewhere/books'), ('$ROOT/srv/booklore-extra/books')" \
    -c "INSERT INTO bookdrop_file (file_path) VALUES ('$ROOT/srv/booklore/bookdrop/new.epub')" \
    -c "INSERT INTO audit_log (description) VALUES ('Library added at $ROOT/srv/booklore/library')"
}

# ─── Stand-ins for the system tools ─────────────────────────────────────────
mkdir -p "$WORK/bin" "$WORK/health/api/v1"
cat > "$WORK/bin/systemctl" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$WORK/systemctl.log"
[ "\$1 \$2" = "start trove" ] && [ -e "$WORK/fail-start" ] && exit 1
exit 0
EOF
printf '#!/usr/bin/env bash\n[ -e "%s/fail-visudo" ] && exit 1\ngrep -q "NOPASSWD" "${@: -1}"\n' "$WORK" > "$WORK/bin/visudo"
printf '#!/usr/bin/env bash\necho "(journal)"\n' > "$WORK/bin/journalctl"
chmod +x "$WORK/bin/"*
echo ok > "$WORK/health/api/v1/healthcheck"
(cd "$WORK/health" && exec python3 -m http.server "$HEALTH_PORT" --bind 127.0.0.1 >/dev/null 2>&1) &
HEALTH_PID=$!

export TROVE_MIGRATE_ROOT="$ROOT" TROVE_MIGRATE_SUDO="" \
  TROVE_MIGRATE_SYSTEMCTL="$WORK/bin/systemctl" TROVE_MIGRATE_VISUDO="$WORK/bin/visudo" TROVE_MIGRATE_JOURNALCTL="$WORK/bin/journalctl" \
  TROVE_MIGRATE_PSQL="$PG_BIN/psql -h $PGSOCK -p $PGPORT -U postgres" \
  TROVE_MIGRATE_PG_DUMP="$PG_BIN/pg_dump -h $PGSOCK -p $PGPORT -U postgres" \
  TROVE_MIGRATE_HEALTH_URL="http://127.0.0.1:$HEALTH_PORT/api/v1/healthcheck" TROVE_MIGRATE_HEALTH_TIMEOUT=10
sleep 1

booklore_layout_intact() {
  [ -d "$ROOT/opt/booklore" ] && [ ! -e "$ROOT/opt/trove" ] && [ -f "$ROOT/srv/booklore/config/cover.jpg" ] && [ ! -e "$ROOT/srv/trove" ] \
  && [ -f "$ROOT/etc/booklore/booklore.env" ] && [ ! -e "$ROOT/etc/trove" ] \
  && [ -f "$ROOT/etc/systemd/system/booklore-api.service" ] && [ -f "$ROOT/etc/systemd/system/booklore-api.service.d/jvm-memory.conf" ] \
  && [ ! -e "$ROOT/etc/systemd/system/trove.service" ] && [ -f "$ROOT/etc/sudoers.d/booklore" ] && [ ! -e "$ROOT/etc/sudoers.d/trove" ] \
  && [ "$(psql_su -d postgres -c "SELECT string_agg(datname, ',') FROM pg_database WHERE datname IN ('booklore','trove')")" = "booklore" ] \
  && [ "$(psql_su -d postgres -c "SELECT string_agg(rolname, ',') FROM pg_roles WHERE rolname IN ('booklore','trove')")" = "booklore" ] \
  && [ "$(psql_su -d booklore -c "SELECT path FROM library_path ORDER BY id LIMIT 1")" = "$ROOT/srv/booklore/library" ] \
  && [ "$(git -C "$ROOT/opt/booklore" remote get-url origin)" = "git@github-booklore:X2-Consult/booklore.git" ]
}

# ─── 1. Dry run changes nothing ─────────────────────────────────────────────
echo "Dry run"
make_layout
out="$(bash "$ROOT/opt/booklore/scripts/migrate-to-trove.sh" --dry-run)"
check "lists the database rename"      grep -q "rename 'booklore' -> 'trove'" <<< "$out"
check "counts stored paths to rewrite" grep -q "rewrite 1 stored path(s) in library_path.path" <<< "$out"
check "keeps the SSH alias in the remote" grep -q "git@github-booklore:X2-Consult/trove.git" <<< "$out"
check "changes nothing"                booklore_layout_intact

# ─── 2. Migration ───────────────────────────────────────────────────────────
echo "Migration"
bash "$ROOT/opt/booklore/scripts/migrate-to-trove.sh" --yes > "$WORK/migrate.out" 2>&1 || { cat "$WORK/migrate.out"; bad "migration exited non-zero"; }
env_new="$ROOT/etc/trove/trove.env"; unit_new="$ROOT/etc/systemd/system/trove.service"
check "checkout moved"                  test -d "$ROOT/opt/trove" -a ! -e "$ROOT/opt/booklore"
check "data moved"                      test -f "$ROOT/srv/trove/config/cover.jpg" -a ! -e "$ROOT/srv/booklore"
check "env file moved, old folder gone" test -f "$env_new" -a ! -e "$ROOT/etc/booklore"
check "DATABASE_URL points at trove"    grep -qx "DATABASE_URL=jdbc:postgresql://localhost:$PGPORT/trove" "$env_new"
check "DATABASE_USERNAME is trove"      grep -qx "DATABASE_USERNAME=trove" "$env_new"
check "BOOKLORE_ keys renamed"          grep -qx "TROVE_RAR_BIN=/usr/local/bin/rar" "$env_new"
check "TROVE_REPO_DIR added"            grep -qx "TROVE_REPO_DIR=$ROOT/opt/trove" "$env_new"
check "password kept verbatim"          grep -qx "DATABASE_PASSWORD=pw'with\"quotes" "$env_new"
check "unit uses the new paths"         grep -qx "WorkingDirectory=$ROOT/opt/trove/booklore-api" "$unit_new"
check "unit uses the new env file"      grep -qx "EnvironmentFile=$env_new" "$unit_new"
check "unit uses the new data folder"   grep -qx "Environment=APP_PATH_CONFIG=$ROOT/srv/trove/config" "$unit_new"
check "unit description says Trove"     grep -q "^Description=Trove backend" "$unit_new"
check "JVM drop-in moved"               test -f "$ROOT/etc/systemd/system/trove.service.d/jvm-memory.conf" -a ! -e "$ROOT/etc/systemd/system/booklore-api.service.d"
check "UI unit moved"                   test -f "$ROOT/etc/systemd/system/trove-ui.service" -a ! -e "$ROOT/etc/systemd/system/booklore-ui.service"
check "sudoers rule renamed"            grep -q "restart trove, .*restart trove-ui" "$ROOT/etc/sudoers.d/trove"
check "old sudoers rule removed"        test ! -e "$ROOT/etc/sudoers.d/booklore"
check "database renamed"                test "$(psql_su -d postgres -c "SELECT count(*) FROM pg_database WHERE datname='trove'")" = 1
check "role renamed, password works"    can_login trove
check "library path rewritten"          test "$(psql_su -d trove -c "SELECT path FROM library_path ORDER BY id LIMIT 1")" = "$ROOT/srv/trove/library"
check "other paths untouched"           test "$(psql_su -d trove -c "SELECT string_agg(path, ',' ORDER BY id) FROM library_path WHERE id > 1")" = "/mnt/elsewhere/books,$ROOT/srv/booklore-extra/books"
check "bookdrop path rewritten"         test "$(psql_su -d trove -c "SELECT file_path FROM bookdrop_file")" = "$ROOT/srv/trove/bookdrop/new.epub"
check "audit history untouched"         grep -q "srv/booklore/library" <<< "$(psql_su -d trove -c "SELECT description FROM audit_log")"
check "git remote keeps the SSH alias"  test "$(git -C "$ROOT/opt/trove" remote get-url origin)" = "git@github-booklore:X2-Consult/trove.git"
check "database dump taken"             test -s "$(ls -d "$ROOT"/var/backups/trove-migration/*/ | tail -1)booklore.dump"
check "services swapped in order"       grep -q "stop booklore-api" "$WORK/systemctl.log"
check "trove started"                   grep -q "^start trove$" "$WORK/systemctl.log"

# ─── 3. Rollback ────────────────────────────────────────────────────────────
echo "Rollback"
bash "$ROOT/opt/trove/scripts/migrate-to-trove.sh" --rollback --yes > "$WORK/rollback.out" 2>&1 || { cat "$WORK/rollback.out"; bad "rollback exited non-zero"; }
check "everything back as Booklore"     booklore_layout_intact
check "old role password works"         can_login booklore
check "env file restored exactly"       cmp -s "$ROOT/etc/booklore/booklore.env" "$(ls -d "$ROOT"/var/backups/trove-migration/*/ | tail -1)booklore.env"
check "second rollback refused"         bash -c "! bash '$ROOT/opt/booklore/scripts/migrate-to-trove.sh' --rollback --yes >/dev/null 2>&1"

# ─── 4. A failing step is undone automatically ──────────────────────────────
echo "Failure mid-migration"
make_layout
touch "$WORK/fail-visudo"   # the sudoers step fails, after the database, moves, env file and units are done
bash "$ROOT/opt/booklore/scripts/migrate-to-trove.sh" --yes > "$WORK/fail.out" 2>&1 && bad "migration should have failed" || true
rm -f "$WORK/fail-visudo"
check "reports the undo"                grep -q "undoing the steps already done" "$WORK/fail.out"
check "everything back as Booklore"     booklore_layout_intact

# ─── 5. Unhealthy start rolls back (non-interactive) ────────────────────────
echo "Unhealthy start"
make_layout
touch "$WORK/fail-start"
bash "$ROOT/opt/booklore/scripts/migrate-to-trove.sh" --yes < /dev/null > "$WORK/unhealthy.out" 2>&1 && bad "migration should have failed" || true
rm -f "$WORK/fail-start"
check "reports the rollback"            grep -q "Rolled back" "$WORK/unhealthy.out"
check "everything back as Booklore"     booklore_layout_intact

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
