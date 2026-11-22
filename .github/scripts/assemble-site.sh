#!/usr/bin/env bash
# Assembles the GitHub Pages site into a staging directory: site/ holds the hand-written HTML, CSS
# and icon, and the screenshots are taken from design/screenshots/ so they are not duplicated in
# Git. Used by .github/workflows/pages.yml and by `mise pages-preview`.
set -euo pipefail

out="${1:-_site}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

mkdir -p "$out/screenshots"
cp "$repo/site/index.html" "$repo/site/styles.css" "$repo/site/icon.svg" "$out/"
cp "$repo"/design/screenshots/*.png "$out/screenshots/"

echo "Assembled the site in $out"
