#!/usr/bin/env bash
# Runs the rclone the desktop app image bundles (downloadRclone, staged into its app resources), so
# a package that lost the engine, or its execute bit, fails here instead of in a user's cloud sync.
# Run after :desktopApp:createDistributable; from Git Bash on Windows.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

engine=$(find desktopApp/build/compose/binaries/main/app -path '*/resources/rclone/rclone*' -type f -print -quit)
[ -n "$engine" ] || {
    echo "No rclone in the app image" >&2
    exit 1
}
"$engine" version
