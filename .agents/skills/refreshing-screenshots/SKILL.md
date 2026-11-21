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
| `search.png` | 2560×1600 | A full-text query with several tagged hits (e.g. `invoice`) |
| `preview.png` | 2560×1600 | A document open in the side preview |
| `details.png` | 2560×1600 | Details tab of a curated document (correspondent, type, date, coloured tag) |
| `filters.png` | 2560×1600 | Filter sheet with two tags selected |
| `tags.png` | 2560×1600 | Tags screen: nested, colour-coded tree |
| `ai-settings.png` | 2560×1600 | Settings → Assistant, enabled, provider + model filled in |
| `phone-library.png`, `phone-details.png`, `phone-tags.png` | 824×1800 | Same library on a phone |

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

## 2. Desktop shots

Start the sandbox with the demo library (`driving-the-desktop-app`), onboard, dismiss the
Getting-started card, then for each shot: set up the state, move the pointer to a corner,
`"$helper" shot "$S" design/screenshots/<name>.png`, OCR the saved file to confirm it shows
what you meant (no "No matches", no tooltip, no garbled query, the right toggle state).

Capture at 2× — start the sandbox with `NPN_JAVA_OPTS='-Dsun.java2d.uiScale=2.0'` (see the
scale rules in `driving-the-desktop-app`). The 2560×1600 files are 1280×800 dp; a 1× capture
stretched to the README is visibly blurry.

- Sort: the sort chip under the search field → Document date, Descending.
- Filters: the Filters icon in the top bar (hover the icons for tooltips to find it).
- AI settings: Settings → Assistant → flip "Turn on the assistant" once, pick a provider,
  type a model id (e.g. `gpt-4o-mini`); leave the API key empty. Settings' Library pane shows
  the sandbox path — don't capture that pane.

## 3. Phone shots

Needs the user's phone over adb (`adb devices`). The debug build (`<applicationId>.debug`)
installs beside the release one: `mise deploy`.

No phone available: the desktop app switches to the same shared phone layout in a narrow
window. Start the sandbox with `NPN_SIZE=824x1800` **and** `NPN_JAVA_OPTS='-Dsun.java2d.uiScale=2.0'`
(824×1800 device pixels = 412×900 dp, the size in the table), then capture with the helper.
Without the 2× option the same window is a desktop-wide layout.

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
