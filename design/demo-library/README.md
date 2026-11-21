# Demo library

A small fictional library for screenshots (README, Play Store listing) and manual
testing. Everything in it is invented: the people, companies, addresses, bank
accounts, tax numbers and amounts do not refer to anyone or anything real. Keep
it that way — never add a scanned personal document or a real correspondence to
this folder.

## Layout

- `generate.py` — writes the 18 fictional PDFs into `library/`. Run it through
  `mise demo-library` (or `uv run --with fpdf2 generate.py`).
- `library/` — the library folder itself: the PDFs plus `.npn/`, the journal that
  holds the curated metadata (titles, correspondents, document types, dates,
  the nested, coloured tag set). Regenerating the PDFs alone leaves the journal
  pointing at hashes that no longer exist, so after changing `generate.py`,
  regenerate with `--fresh` and re-curate in the app, or leave the PDFs alone.

The documents have real text layers (no scans), so full-text search finds them
without OCR, and their first pages carry coloured letterheads so grid thumbnails
tell them apart.

## Using it for screenshots

Desktop: point the app at a copy of `library/` as its library folder (for
example `Documents/NoPapersNeeded` in a scratch HOME), let it import, and shoot.
The journal brings every title, field and tag along, so no curation is needed.

Android: the debug build keeps its demo library in
`Documents/NoPapersNeeded-debug` on the device. Refresh it with:

```sh
adb shell rm -rf /sdcard/Documents/NoPapersNeeded-debug
adb push design/demo-library/library /sdcard/Documents/NoPapersNeeded-debug
```

Open the app and the rescan brings the same library up, metadata included.

## Regenerating

`mise demo-library` rewrites `library/*.pdf` in place. Add `-- --fresh` to also
wipe `library/.npn` (you lose the curation and must redo it in the app), and
`-- --out DIR` to write elsewhere.
