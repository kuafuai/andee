#!/usr/bin/env bash
# Builds the release dmg around the *signed* Andee.app with the Applications
# symlink and the drag layout (app on the left, Applications on the right, an
# arrow background, a fixed window).
#
# jpackage's dmg (what Compose Desktop's packageDmg produces) has no
# Applications symlink and no window layout, so people double-click the app and
# run it straight from the mounted image. dmgbuild writes the .DS_Store that
# carries the layout directly — Finder's AppleScript no longer writes .DS_Store
# on current macOS (measured on Sequoia), so the osascript route was abandoned.
#
# Usage: make-dmg.sh [--check-tools | <Andee.app> <out.dmg>]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PYTHON="${ANDEE_DMG_PYTHON:-python3}"

check_tools() {
    if ! command -v "$PYTHON" >/dev/null 2>&1 || \
       ! "$PYTHON" -c "from importlib.metadata import version; import sys; sys.exit(version('dmgbuild') != '1.6.7')" 2>/dev/null; then
        echo "DMG packaging requires Python with dmgbuild==1.6.7." >&2
        echo "Install it with: python3 -m pip install -r desktop/dmg/requirements.txt" >&2
        echo "For an isolated environment, see README.md (Mac packaging)." >&2
        echo "Set ANDEE_DMG_PYTHON to that environment's Python executable." >&2
        exit 2
    fi
}

check_tools
if [[ "${1:-}" == "--check-tools" ]]; then
    exit 0
fi
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <Andee.app> <out.dmg>" >&2
    exit 2
fi

APP="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUT="$2"

mkdir -p "$(dirname "$OUT")"
export ANDEE_APP="$APP"
export ANDEE_BACKGROUND="$HERE/background.png"
export ANDEE_VOLUME_ICON="$HERE/../icon/Andee.icns"

if [[ ! -f "$ANDEE_BACKGROUND" || -n "${ANDEE_REGENERATE_BACKGROUND:-}" ]]; then
    "$PYTHON" "$HERE/make-background.py"
fi

"$PYTHON" -m dmgbuild "Andee" "$OUT" -s "$HERE/dmgbuild-settings.py"
