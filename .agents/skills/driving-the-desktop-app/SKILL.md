---
name: driving-the-desktop-app
description: Launch the No Papers Needed desktop app in an isolated sandbox (own user.home, Xvfb display) and drive it headlessly with xdotool + OCR. Use when you need to see or click through the real desktop UI — screenshots, checking a UI change, reproducing a visual bug — without touching the developer's real library.
---

# Driving the desktop app

The developer runs this app daily against their **real library**. A carelessly
launched instance opens it. Everything below exists to make that impossible.

## Isolation: the two traps

1. **`./gradlew :desktopApp:run` ignores your environment.** The app JVM is forked by the
   Gradle daemon and inherits the daemon's `HOME`, so `HOME=/tmp/x ./gradlew :desktopApp:run`
   still opens the real library. Never use `run` (or `mise hot`) for sandboxed work.
2. **JDK 25 ignores `$HOME`.** `user.home` comes from the passwd entry; only
   `-Duser.home=<dir>` moves it. `PlatformDirs` (core `desktopMain`) prefers
   `XDG_DATA_HOME`/`XDG_CONFIG_HOME` over `user.home`, so pin those too.

The fix is the self-contained uber-jar (`:desktopApp:packageUberJarForCurrentOS` →
`desktopApp/build/compose/jars/no-papers-needed-linux-x64-<version>.jar`) launched directly
with `-Duser.home` and the XDG vars. [scripts/sandbox.sh](scripts/sandbox.sh) does exactly
that and refuses to continue unless the app's state dir appeared inside the sandbox.

**Check before you click.** After launch, OCR the first screen. A fresh sandbox shows the
welcome screen ("Every paper you keep, found in seconds." / "Get started"), or the demo
library you passed. Any other populated library means you are on the real one: stop
immediately, click nothing, tell the user.

## Launch

```sh
S=<scratchpad>/npn-desktop          # never inside the real $HOME
helper=.agents/skills/driving-the-desktop-app/scripts/sandbox.sh
"$helper" start "$S" design/demo-library/library
```

`start` builds the uber-jar with Gradle: run one Gradle build at a time across worktrees
(AGENTS.md).

- Optional 3rd arg copies a library to `$S/home/Documents/NoPapersNeeded`: the folder
  onboarding proposes, so the "Use this folder" button picks it with no native file dialog.
- Xvfb selects a free display (or requires the requested `NPN_DISPLAY` to be free), with the window moved to 0,0 and resized to 2560×1600 (`NPN_DISPLAY`, `NPN_SIZE`).
  Plain Xvfb has no window manager: the window is undecorated and density is 1×.
  `NPN_SIZE` is **device pixels**, so at 2× the same window is half as many dp.
- **Which knob works depends on the launcher, and each one hides the other:**
  - Sandbox (uber-jar, `java -jar`): `NPN_JAVA_OPTS='-Dsun.java2d.uiScale=2.0'`, and the
    factor is **literal**. In the same 2560×1600 window the whole UI renders at 2× — a
    1280×800 dp desktop, what a HiDPI screen at 200% system scaling shows. Measured by OCR
    on the welcome heading: 332 px wide at 1×, 655 px at 2.0, 1319 px at 4.0. So `4.0` really
    is 4× and clips the UI; a capture that "came out zoomed" is this, not a DPI bug.
  - Anything that passes `-Dcompose.application.configure.swing.globals=true` — the packaged
    launcher does (`jpackage` writes it into `lib/app/no-papers-needed.cfg`) — sets
    `skiko.linux.autodpi=true`, and Skiko's `Setup.init` **overwrites** `sun.java2d.uiScale`
    from the X resource `Xft.dpi`. There `uiScale` does nothing at all; `Xft.dpi: 192` renders
    2×, where the nominal 96 is 1× (both measured). That is why "the option is ignored": it
    was never the option.
  - `GDK_SCALE=2` and `Xvfb -dpi 192` changed nothing with autodpi on (measured); neither is
    a shortcut.
  - So don't debug scale in a packaged build, or in `mise hot` (whose JBR 21 measures a
    different runtime): capture from the sandbox, where one property is enough. Changing it
    needs `stop` then `start` (`start` refuses a scratch dir that still has a display).
- **The Xvfb resource database is wiped between clients.** An X server resets, clearing root
  properties, when its last client disconnects: `printf 'Xft.dpi: 192' | xrdb -merge` exits 0
  and the value is gone before the app connects. Keep a client open (`xprop -root -spy &`)
  and confirm with `xrdb -query`, or you will conclude Xft.dpi "does nothing here".
- **Check the scale, don't trust the property.** OCR one heading at 1× and again at the
  chosen scale; its box must be proportionally wider. A half-visible or clipped layout means
  the app is drawing larger than you asked for.
- At 2× every click coordinate doubles. OCR and xdotool work in device pixels; convert sizes
  from dp by multiplying by the scale, and take click points from OCR unchanged.
- Onboarding: Get started → Use this folder → continue through the remaining steps. Then
  dismiss the "Getting started" checklist card with the close button at its right edge.

## See: screenshot + OCR

Look at the screenshot to judge layout and state; take click coordinates from OCR, never
from eyeballing pixels:

```sh
"$helper" shot "$S" "$S/shot.png"
uv run --quiet --with rapidocr-onnxruntime --with pillow \
  python .agents/skills/driving-the-desktop-app/scripts/ocr.py "$S/shot.png" ["phrase"] [--crop x0,y0,x1,y1]
```

Coordinates are full-image pixels. Drop hits with `conf < 0.6` (icons misread as CJK glyphs).
OCR mangles spaces in headings (`Whereshouldyourlibrarylive?`); fuzzy-match, don't grep exact.

## Act: xdotool

```sh
DISPLAY=$(cat "$S/display")
export DISPLAY
xdotool mousemove X Y click 1
xdotool type --delay 40 'Text – with en dash'     # keep LANG/LC_ALL UTF-8 or non-ASCII fails
xdotool key ctrl+a BackSpace Return Escape
xdotool mousemove X Y; sleep 1                    # hover shows a tooltip: identifies icon buttons
```

Lessons that cost time:

- **Verify every step.** Screenshot + OCR after each click; layouts reflow (selecting a chip
  moves the next one, a closing menu lets the click fall through to a document behind it).
- **Toggles toggle.** Clicking a switch's label row *and* the switch flips it twice. Read the
  state, then click once.
- **Clear text fields first** (`ctrl+a BackSpace`) and type slowly; fast typing into a search
  field with live results garbles it.
- **Read the composable instead of guessing.** When a control won't respond, find it in
  `composeApp/src/commonMain/kotlin/dev/npn/app/ui/` — e.g. adding a tag is "type the name in
  the Add tag field, then click the + at that row's right end".
- **Non-text controls** (colour swatches, chips' selected state): sample pixels with Pillow
  around an OCR anchor (`img.getpixel((x, y))`) rather than guessing offsets.
- **Mouse hover leaks into screenshots.** Move the pointer to an empty corner before a
  capture.
- For long sequences (curating many documents), write a driver script that loops
  OCR → click → verify, and run it in the background; `python -u` so its log isn't buffered.

## Stop

```sh
"$helper" stop "$S"
```

It signals the sandbox JVM to exit, waits for it (force-killing it after 15 s), and only then kills Xvfb — killing Xvfb first
crashes the JVM in libX11 and drops `hs_err_pid*.log`/core files in the working directory.
Don't `pkill -f` with a pattern that also matches your own shell's command line. Afterwards
check `git status` for stray files (hs_err logs, OCR crops saved into the repo).
