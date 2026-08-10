#!/bin/bash
# Make `git push` publish a build, so pushing a change is all it takes to update the devices.
#
# Opt-in, and installed rather than committed into .git/hooks by hand, because a hook that runs a
# two-minute build on every push is a thing you should choose deliberately.
#
#   tools/install-hooks.sh          install
#   tools/install-hooks.sh --remove take it back out
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK="$REPO/.git/hooks/pre-push"

if [ "${1:-}" = "--remove" ]; then
  rm -f "$HOOK"
  echo "pre-push hook removed"
  exit 0
fi

cat > "$HOOK" <<'HOOKEOF'
#!/bin/bash
# Publish a build on push. Installed by tools/install-hooks.sh.
#
# pre-push rather than post-commit: a commit is a save point and often one of several, while a
# push is the moment you have decided a change is finished. Publishing on every commit would put
# half-finished work on the televisions.
#
# Never blocks the push. If the build fails the push still goes ahead and says so - a broken
# build is a thing to fix, not a reason to lose the commits - and if the publisher is unreachable
# the apk simply is not published, which is the same as not having run it.
REPO="$(git rev-parse --show-toplevel)"
echo "==> publishing a build (pre-push hook)"
if "$REPO/tools/deploy.sh"; then
  echo "==> published"
else
  echo "==> deploy failed - pushing anyway, nothing was published" >&2
fi
exit 0
HOOKEOF

chmod +x "$HOOK"
echo "pre-push hook installed at .git/hooks/pre-push"
echo "  git push now builds, installs to anything awake, and publishes the apk"
echo "  remove it with: tools/install-hooks.sh --remove"
