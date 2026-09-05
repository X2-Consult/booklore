#!/usr/bin/env bash
#
# Promote develop -> master (the production branch).
#
# master only ever fast-forwards to develop's HEAD, so the two branches can never
# diverge - master is always a strict prefix of develop's history. Pushing master
# triggers .github/workflows/master-pipeline.yml, which runs the tests, computes the
# next vX.Y.Z tag (patch bump by default; put #minor or #major in a commit subject
# to change it) and drafts a GitHub release.
#
# After this finishes, deploy on the production server with:  ./deploy.sh
#
set -euo pipefail

log() { echo ">> $*"; }
die() { echo ">> ERROR: $*" >&2; exit 1; }

cd "$(dirname "${BASH_SOURCE[0]}")"

git diff --quiet && git diff --cached --quiet || die "Working tree is dirty - commit or stash first."

ORIG_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
restore() { git checkout --quiet "$ORIG_BRANCH" 2>/dev/null || true; }
trap restore EXIT

log "Fetching..."
git fetch --quiet origin

git rev-parse --verify --quiet origin/develop >/dev/null || die "origin/develop not found."

log "Fast-forwarding master to origin/develop..."
git checkout --quiet master 2>/dev/null || git checkout --quiet -b master origin/master
git merge --ff-only origin/develop || die "master cannot fast-forward to develop (they have diverged - a hotfix on master was not merged back). Resolve manually."

if git diff --quiet "@{upstream}" 2>/dev/null; then
  log "master is already up to date with develop - nothing to promote."
  exit 0
fi

log "Pushing master (CI will tag + draft a release)..."
git push origin master

log "Done. Deploy on the production server: ./deploy.sh"
