---
name: refreshing-screenshots
description: Retake the README and Play Store screenshots from the app's own UI over the fictional demo library, then re-render the Play Store assets. Use when the user asks to update, refresh or replace screenshots, or to show a new feature in them.
---

# Refreshing the screenshots

The eight files in `design/screenshots/` are produced by `ScreenshotCaptureTest`
(`composeApp/src/desktopTest/kotlin/dev/npn/app/screenshots/`): the app's real composables, wired
to a throwaway copy of `design/demo-library/library`, rendered offscreen at 2× into an
`ImageComposeScene`. Nothing is driven through a window, so the sizes are exact, every frame is
deterministic, and the fixture is never written to. No Xvfb, xdotool, OCR or phone is involved.

For looking at the app while developing (not for these files), `driving-the-desktop-app` is still
the tool.

## What exists

| File | Size | Shows |
|---|---|---|
| `design/screenshots/library.png` | 2560×1600 | Library grid, sorted by document date |
| `search.png` | 2560×1040 | A full-text query with several tagged hits (`October`) |
| `preview.png` | 2560×1600 | A document open in the side preview |
| `details.png` | 2560×1600 | Details tab of a curated document (correspondent, type, date, coloured tag) |
| `filters.png` | 2560×1600 | Filter panel with two tags included, matching any |
| `phone-library.png`, `phone-details.png`, `phone-tags.png` | 824×1800 | The same library on a phone |

`search.png` is cropped by the test to the content: the first result row and its tag chips end at
about y=1035, so 2560×1040.

`README.md` ("A closer look", "On your phone") and the SVGs in `design/play-store/src/` reference
these names; a new shot means a README edit, and a renamed one breaks the Play Store render.

## 1. Run the capture

```sh
NPN_SCREENSHOTS=/tmp/npn-shots ./gradlew :composeApp:desktopTest \
    --tests 'dev.npn.app.screenshots.ScreenshotCaptureTest' --rerun
cp /tmp/npn-shots/*.png design/screenshots/
```

The test is opt-in — without `NPN_SCREENSHOTS` it is skipped, so CI never writes files — and it
prints no output on success. A run takes about a minute; it opens the library once and gives every
shot a fresh scene over that session.

It also sets what a fresh install would show (grid, document date descending, the Getting started
card dismissed): the demo library's journal carries the metadata, not the device's settings, so
nothing has to be prepared by hand.

## 2. What each shot drives

Change a state by changing the test, never by editing a PNG. Each shot starts from the library as
it opens, so the order in `capture()` does not matter.

- **library.png** — as it opens: 18 documents, grid, Document date · Descending.
- **search.png** — Ctrl+F (the app's own focus path, which draws the field's focus ring), type
  `October`, wait for the nine hits, Escape. Escape closes the suggestions and leaves the query:
  while the search field has focus, the library's own Escape shortcut stands down.
- **preview.png** — click the `Account statement – Q3 2026` card and wait for the page bitmap
  (the page arrives asynchronously, decoded off the UI thread).
- **details.png** — open the same document, click the pane's expand button, then set the split
  through the divider's `SetProgress` semantics to 0.3452 — the same range the drag handle reports.
  The form then gets two thirds of the width, so Title…Tags fit above the fold; at the side pane's
  800 dp they do not.
- **filters.png** — Filters, `Match any`, `Bills.Energy` and `Bills.Water` under Include tags, then
  Apply (which closes the panel), then Filters again: the shot is the applied filter with its
  panel open over it. The include chips are picked after the panel's "Include tags" heading, since
  the library behind shows the same tag names.
- **phone-*.png** — the same app at 824×1800 at 2×, i.e. 412×900 dp, which `WindowWidthClass`
  sees as compact: grid and bottom bar, the details screen (opened from the grid, so it counts all
  eighteen, with the Details tab picked after swiping the grid to the document), and the Tags
  screen.

The player of a PDF page, the thumbnails and the first index build are asynchronous: each step
waits on what it needs (a title, the page's `Page 1` description, the result count) rather than
sleeping, and the scene's clock is advanced a frame per render, so animations (tab indicator,
selection pill) settle where the capture expects them.

## 3. The demo library

`design/demo-library/` (see its README): `generate.py` writes 18 fictional PDFs with real text
layers (search works without OCR), and `library/` is the **curated** library folder — PDFs plus
`.npn/` journal with titles, correspondents, types, dates, nested coloured tags and the AI setting.
Use `library/` as-is; the capture test copies it to a temporary directory, so a run never changes
it and there is nothing to copy back.

- Everything in it is invented. Never add a real document, name, address or account.
- Regenerating the PDFs (`mise demo-library`) changes their hashes and orphans the curation; only do
  it with `-- --fresh` and re-curate in the app afterwards.
- Prefer a table-heavy page for `preview.png` and `details.png` (the bank statement works): the
  generated paragraphs are justified, so prose pages show stretched word gaps and odd line breaks
  in a close-up.

## 4. Play Store assets

The SVGs embed `../../screenshots/*.png`, so re-render after any screenshot change:

```sh
cd design/play-store
for f in src/phone-*.svg src/tablet-*.svg; do n=$(basename "$f" .svg); resvg --resources-dir src "$f" "$n.png"; done
```

## 5. Finish

- README: update captions/alt text if a shot's content changed; README stays end-user promo copy
  (AGENTS.md), no technical detail.
- `git status`: only the shots whose content changed should show; an unchanged shot that still
  differs is a determinism bug in the capture test worth reporting.
- Report what changed, and ask before committing (`[<branch>] Imperative summary`).
