#!/usr/bin/env bash
# Starts the Tesseract a Windows build bundles (prepareWindowsTesseract) and checks it lists every
# model in desktopApp/tessdata-fast.txt. A DLL missing from the bundle would otherwise only show up
# as silently empty OCR on users' machines. Run from Git Bash after :desktopApp:createDistributable.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

tess=$(find desktopApp/build/compose/binaries -name tesseract.exe -print -quit)
[ -n "$tess" ] || {
    echo "No tesseract.exe in the app image" >&2
    exit 1
}
du -sh "$(dirname "$tess")"
langs=$(TESSDATA_PREFIX="$(dirname "$tess")/tessdata" "$tess" --list-langs | tr -d '\r')
echo "$langs"

missing=0
while read -r lang _; do
    grep -qx "$lang" <<<"$langs" || {
        echo "The bundled Tesseract lacks the $lang model" >&2
        missing=1
    }
done < <(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' desktopApp/tessdata-fast.txt)
exit "$missing"
