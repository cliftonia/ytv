#!/bin/bash
# Make `git push` build and install, so pushing a change also updates the televisions in the house.
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
# Build and install on push. Installed by tools/install-hooks.sh.
#
# pre-push rather than post-commit: a commit is a save point and often one of several, while a
# push is the moment you have decided a change is finished. Publishing on every commit would put
# half-finished work on the televisions.
#
# Never blocks the push. If the build fails the push still goes ahead and says so - a broken
# build is a thing to fix, not a reason to lose the commits - and if no television is awake the
# apk simply is not installed, which is what the release workflow exists to cover.
REPO="$(git rev-parse --show-toplevel)"
echo "==> building and installing (pre-push hook)"
if "$REPO/tools/deploy.sh"; then
  echo "==> installed"
else
  echo "==> deploy failed - pushing anyway, nothing was installed" >&2
fi
exit 0
HOOKEOF

chmod +x "$HOOK"
echo "pre-push hook installed at .git/hooks/pre-push"
echo "  git push now builds and installs to any television that is awake"
echo "  remove it with: tools/install-hooks.sh --remove"
