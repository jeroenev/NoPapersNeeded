---
name: refreshing-screenshots
description: Retake the README and Play Store screenshots from the real app (desktop and phone) using the fictional demo library, then re-render the Play Store assets. Use when the user asks to update, refresh or replace screenshots, or to show a new feature in them.
---

# Refreshing the screenshots

Screenshots are real captures of the app showing the **fictional demo library**, never the
developer's own documents. Desktop work goes through the `driving-the-desktop-app` skill;
read it first — its isolation rules are not optional.

Tools: Xvfb, xdotool and ImageMagick (`driving-the-desktop-app`), `uv` (OCR and cropping),
`resvg` (Play Store render) and, for phone shots, `adb` with the user's phone.

## What exists

| File | Size | Shows |
|---|---|---|
| `design/screenshots/library.png` | 2560×1600 | Library grid, sorted by document date |
| `search.png` | 2560×1040 | A full-text query with several tagged hits (e.g. `October`) |
| `preview.png` | 2560×1600 | A document open in the side preview |
| `details.png` | 2560×1600 | Details tab of a curated document (correspondent, type, date, coloured tag) |
| `filters.png` | 2560×1600 | Filter sheet with two tags selected |
| `phone-library.png`, `phone-details.png`, `phone-tags.png` | 824×1800 | Same library on a phone |

`search.png` is cropped to the content: the first result row and its tag chips end at about
y=1035, so 2560×1040. Cropping to 1000 cuts the tags off.

Check current sizes with `identify design/screenshots/*.png` and match them. `README.md`
("A closer look", "On your phone") and the SVGs in `design/play-store/src/` reference these
names; a new shot means a README edit, and a renamed one breaks the Play Store render.

## 1. The demo library

`design/demo-library/` (see its README): `generate.py` writes 18 fictional PDFs with real
text layers (search works without OCR), and `library/` is the **curated** library folder —
PDFs plus `.npn/` journal with titles, correspondents, types, dates, nested coloured tags
and the AI setting. Use `library/` as-is; no curation needed.

- Everything in it is invented. Never add a real document, name, address or account.
- Regenerating the PDFs (`mise demo-library`) changes their hashes and orphans the curation;
  only do it with `-- --fresh` and re-curate in the app afterwards.
- If you change the library in the app (new tags, settings for a new feature), copy the
  sandbox's folder back when done:
  `rsync -a --delete "$S/home/Documents/NoPapersNeeded/" design/demo-library/library/`.
  It's all write-once journal objects plus one throwaway device id: safe to commit.
- Prefer a table-heavy page for `preview.png` and `details.png` (the bank statement works): the
  generated paragraphs are justified, so prose pages show stretched word gaps and odd line
  breaks in a close-up.

## 2. Desktop shots

Start the sandbox with the demo library (`driving-the-desktop-app`), onboard, dismiss the
Getting-started card, then for each shot: set up the state, move the pointer to a corner,
`"$helper" shot "$S" design/screenshots/<name>.png`, OCR the saved file to confirm it shows
what you meant (no "No matches", no tooltip, no garbled query, the right toggle state).

Capture at 2× — start the sandbox with `NPN_JAVA_OPTS='-Dsun.java2d.uiScale=2.0'` (see the
scale rules in `driving-the-desktop-app`). The 2560×1600 files are 1280×800 dp; a 1× capture
stretched to the README is visibly blurry.

Only whole scales are honoured: `1.5` and `1.8` silently render at 1× (measured), and at 1×
the grid collapses to 15 tiny columns — so 2× is the only setting that gives these files.

**Measure the pane before composing it.** 2× leaves 1280×800 dp, and a details or filter pane
can be taller than that. On the first capture, OCR the pane's last element (the tag chip,
"Apply filters") and compare its y with 1600. If it sits at the edge or past it, do not scroll:
one wheel notch moves about 110 px and hides the first field's label. Change the composition
instead — the expanded details view below, with a wider form — or the shot will be clipped.

- Sort: the sort chip under the search field → Document date, Descending.
- Filters: the Filters icon in the top bar (hover the icons for tooltips to find it). A tag
  pair such as `Bills.Energy` + `Bills.Water` with Match any applies and shows the union; the
  demo library gives each document a single tag, so Match all finds nothing.
- Details is the one desktop shot taken in the **expanded** document view (the ↗ in the pane
  header), not the side pane: at 2× the side pane is only 800 dp tall and the form needs more,
  so its Tags section falls below the fold. In the expanded view, drag the divider between the
  page and the form left until the hints wrap shorter; then Title…Tags fit without scrolling.
  Check that the tag chip is not cut by the bottom edge.
- AI settings: Settings → Assistant → flip "Turn on the assistant" once, pick a provider,
  type a model id (e.g. `gpt-4o-mini`); leave the API key empty. Settings' Library pane shows
  the sandbox path — don't capture that pane.

### Working the UI by hand

- **Script the run.** A driver that sets up a state, OCRs an anchor and captures makes a retry
  one command instead of a dozen round trips; this set took about 40 capture/OCR rounds, and
  every manual retry re-pays the navigation.
- **Re-OCR the top bar after opening or closing a pane:** the pane spans half the window, so
  the icons shift (the Filters icon sat 1200 px further right with the pane closed).
- **Read the control's composable before fighting it.** A selected tag chip re-orders its row,
  so the next click has to be located again; the Filters sheet keeps its edits until "Apply
  filters".
- **Type into the live search at `--delay 220`.** Faster typing loses keystrokes (`Octobe
  2026`), and the suggestion popup covers the result count until Escape.
- **The caret blinks into captures.** Take three or four frames of the same state and keep one
  with no dark pixel in the caret column (Pillow over the box next to the last glyph).

## 3. Phone shots

Needs the user's phone over adb (`adb devices`). The debug build (`<applicationId>.debug`)
installs beside the release one: `mise deploy`.

No phone available: the desktop app switches to the same shared phone layout in a narrow
window. Start the sandbox with `NPN_SIZE=824x1800` **and** `NPN_JAVA_OPTS='-Dsun.java2d.uiScale=2.0'`
(824×1800 device pixels = 412×900 dp, the size in the table), then capture with the helper.
Without the 2× option the same window is a desktop-wide layout.

Prefer that fallback even when the phone is connected: on the developer's phone the status
bar can no longer be hidden (`policy_control` and System UI demo mode are ignored), so a
native capture is 1080×2376 with their notification icons in the bar, and cropping the bars
leaves 824×1694 — short of the 824×1800 the Play Store SVGs expect without stretching. The
fallback reproduces the phone layout at exactly the right size with no system bars.

In that layout the details form fills all 900 dp: its last tag chip sits against the bottom
edge, and scrolling to expose the Add tag field would hide the Title label. Leave the chip
flush. Open the document from the grid rather than through a search, so the header reads
"N of 18" instead of "1 of 2".

Record the current library from More → Settings → Library. Create a fresh directory on the
selected device and copy the fixture into it:

```sh
demo_dir=$(adb shell mktemp -d /sdcard/Documents/NPN-demo-XXXXXX) &&
  adb push design/demo-library/library/. "$demo_dir/"
```

Select that directory via Settings → Library → Change library. Verify the 18 fictional
documents before capturing. Restore the recorded library when finished.

Capture and locate with OCR the same way:

```sh
adb exec-out screencap -p > "$S/phone.png"
adb shell input tap X Y          # adb shell input keyevent KEYCODE_BACK
```

- A capture can catch the notification shade or lock screen with the user's personal
  notifications. Check every capture; delete any that shows them, never commit one.
- Scale raw captures to 824 wide and centre-crop to 1800 tall:

```sh
uv run --quiet --with pillow python - raw.png design/screenshots/phone-library.png <<'EOF'
import sys
from PIL import Image
img = Image.open(sys.argv[1]); w = 824
new = img.resize((w, round(img.height * w / img.width)), Image.LANCZOS)
top = max(0, (new.height - 1800) // 2)
new.crop((0, top, w, top + 1800)).save(sys.argv[2])
EOF
```

## 4. Play Store assets

The SVGs embed `../../screenshots/*.png`, so re-render after any screenshot change:

```sh
cd design/play-store
for f in src/phone-*.svg src/tablet-*.svg; do n=$(basename "$f" .svg); resvg --resources-dir src "$f" "$n.png"; done
```

## 5. Finish

- README: update captions/alt text if a shot's content changed; README stays end-user
  promo copy (AGENTS.md), no technical detail.
- `git status`: remove stray files (OCR crops, raw captures, `hs_err_pid*.log`). Don't
  stage unrelated changes already in the tree.
- A new `.mise/tasks` script must be executable (`chmod +x`; mise skips it otherwise) and
  pass `shellcheck` (CI runs it).
- Report what changed, and ask before committing (`[<branch>] Imperative summary`).
