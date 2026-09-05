# Branches & releasing

Two long-lived branches:

| Branch | Role |
|--------|------|
| `develop` | Integration. Day-to-day commits land here. CI builds `develop-<sha>` images. |
| `master` | Production. Only ever fast-forwarded from `develop`. CI tags `vX.Y.Z` and drafts a GitHub release on every push. The production server tracks this branch. |

`master` is always a strict prefix of `develop`'s history — they cannot diverge.

## Promote develop → master

```bash
./promote.sh
```

This fast-forwards `master` to `origin/develop` and pushes it. The push triggers
`.github/workflows/master-pipeline.yml`, which runs the test suite, computes the next
version (patch bump by default — put `#minor` or `#major` in a commit subject to override),
tags it, builds the jar + `ghcr.io/x2-consult/booklore` image, and drafts a GitHub release.

## Deploy to production

On the production server (checked out on `master`):

```bash
cd /opt/booklore && ./deploy.sh
```

`deploy.sh` pulls, rebuilds, stamps the running version (`git describe`) into
`/etc/booklore/booklore.env` as `APP_VERSION`, and restarts the service. An admin can also
trigger this from the app itself (sidebar → version → **Update now**).

## Hotfixes

If you must patch production without promoting all of `develop`:

```bash
git checkout -b hotfix/xyz master
# ... fix, commit ...
git checkout master && git merge --ff-only hotfix/xyz && git push origin master
git checkout develop && git merge master && git push origin develop   # back-merge
```

This is the only situation where `master` and `develop` can briefly diverge; the back-merge
resolves it.
