# UX overhaul plan

Companion to `PLAN.md`. Scope: interaction-level polish of the existing v1 screens; no new features
outside the UI layer except where noted (viewer document-to-document navigation needs a small
ViewModel/navigation change).

Current-state findings reference real code locations so each work item is directly actionable.

## 0. Cross-cutting prerequisite: icon strategy

Only `material-icons-core` is bundled (`composeApp/build.gradle.kts:38`), which forces semantically
wrong icons all over the app:

| Usage | Today | Should be |
|---|---|---|
| Filters (top bar) | `Icons.Filled.Build` (wrench), `LibraryScreen.kt:463` | `FilterList` / `Tune` |
| Sort | `Icons.Filled.ArrowDropDown`, `LibraryScreen.kt:490` | `Sort` / `SwapVert` |
| Zoom out | `Icons.Filled.Clear` (X), `PageViewer.kt:140` | `Remove` / `ZoomOut` |
| Zoom in | `Icons.Filled.Add`, `PageViewer.kt:143` | `Add` / `ZoomIn` (fine, but pair consistently) |
| View switcher | `Icons.AutoMirrored.Filled.List` for both states, `LibraryScreen.kt:469` | `GridView` / `ViewList`, reflecting target state |
| Tags/pins | `Icons.Filled.Star` | `Label` / `Sell` |

Decision to take first, because every other section depends on it:

- **Option A (recommended): add `material-icons-extended`.** R8 strips unused vectors on Android.
  On desktop the jar grows (~10 MB); acceptable for a jpackage distributable, and ProGuard for
  Compose desktop can shrink it if it ever matters.
- **Option B: vendor individual `ImageVector` sources** (copy the handful of generated icon files
  into `ui/common/icons/`). Zero dependency weight, slightly annoying to maintain.

Tasks:
1. Add the dependency (or vendored icons) to `gradle/libs.versions.toml` + `composeApp/build.gradle.kts`.
2. Sweep all `Icons.Filled.*` usages and fix the table above (grep for `Icons.` in `composeApp/`).

## 1. Grid density: direct manipulation instead of a settings slider

### Today
- Grid uses `GridCells.Adaptive(columnWidth.dp)` (`ui/library/LibraryScreen.kt:419-420`).
- The only control is a dp slider buried in Settings (`ui/settings/SettingsScreen.kt:197-218`),
  labelled "Grid item size: NNN dp" — pixels exposed to the user, far away from the grid it affects.
- Bounds/default in `core/.../app/Contracts.kt:195-198` (100–280 dp, default 160).
- Persistence already exists: `SettingsRepository.setGridColumnWidth` (SharedPreferences / properties file).

### Target
Zoom the grid where the grid is, never mention dp:

1. **Pinch-to-zoom on the grid (mobile + touchscreens).**
   - Wrap `DocGrid` content in a `Modifier.pointerInput` using `detectTransformGestures` (or
     `rememberTransformableState`) that maps the gesture zoom factor onto `columnWidth`
     (`newWidth = width / gestureZoom`, clamped to `GridSizing` bounds). Inverse mapping matters:
     pinch-out = bigger cells = fewer columns, matching photo-gallery conventions.
   - Update a local `mutableStateOf` continuously during the gesture for a live preview
     (`GridCells.Adaptive` re-flows smoothly), commit to `SettingsRepository` only on gesture end
     (same commit-on-finish pattern the slider already uses, `SettingsScreen.kt:200-211`).
   - Care: the gesture detector must not steal vertical scrolling from `LazyVerticalGrid`. Use
     `awaitEachGesture` and only claim the pointer once two pointers are down (or
     `detectTransformGestures(panZoomLock = true)` on a parent that doesn't consume single-finger
     drags). Test scroll vs pinch on device.
2. **Ctrl+scroll on desktop.**
   - `Modifier.onPointerEvent(PointerEventType.Scroll)` on the grid container; when
     `event.keyboardModifiers.isCtrlPressed`, consume the event and step `columnWidth` by
     ±20 dp per notch (clamped). Without Ctrl, let the grid scroll normally.
   - This modifier is commonMain, so it costs nothing on Android.
3. **Zoom buttons for discoverability (desktop primarily).**
   - Small `+ / −` affordance (e.g. trailing icons in the top bar when grid view is active, or a
     hover-revealed control) stepping through ~5 discrete levels between 100 and 280 dp.
   - Keyboard: `Ctrl+=` / `Ctrl+-` / `Ctrl+0` (reset to default) via `Modifier.onKeyEvent` on the
     library screen root.
4. **Remove the dp slider from Settings** (`SettingsScreen.kt:130-133,197-218`). If a settings
   entry is kept at all, make it a "Grid density: Small / Medium / Large" segmented control —
   but direct manipulation makes it redundant. `GridSizing` and the repository stay as-is.
5. Optional polish: pinch-to-zoom past the minimum cell size in *grid* view could switch to *list*
   view (and vice versa), like Google Photos. Cheap to add once the gesture exists; gate it on the
   gesture ending, not mid-gesture.

Files: `ui/library/LibraryScreen.kt` (DocGrid + LibraryContent), `LibraryViewModel.kt:78-81`
(add a `setGridColumnWidth` passthrough), `ui/settings/SettingsScreen.kt` (remove slider).

## 2. Viewer: zooming, scrolling, page + document navigation

### Today (`ui/detail/PageViewer.kt`, `DocDetailViewModel.kt:68-89`)
- Single page at a time; prev/next arrow buttons only (`PageViewer.kt:148-172`).
- Pinch zoom exists but is uncentered: `graphicsLayer(scaleX/Y = zoom)` scales around the widget
  center, not the gesture centroid, so zooming toward a corner "runs away".
- Pan is unclamped — the page can be flung completely off-screen; pan is ignored below 1× and
  resets only on page change.
- No double-tap zoom, no mouse-wheel/Ctrl+scroll support, no keyboard support, no swipe to change
  page, no fit modes, and **no way to move to the next/previous document** without going back to
  the library (`App.kt:117`).
- Zoom-out button uses the `Clear` (X) icon.

### Target

**2a. Proper zoom/pan mechanics (fix first, everything else builds on it).**
- Centroid-anchored zoom: keep `zoom`, `offsetX/Y` but apply the standard transform update in the
  gesture handler: `offset = (offset - centroid) * zoomChange + centroid + pan`. Render with
  `graphicsLayer { translationX/Y; scaleX/Y; transformOrigin = TopLeft }` so the math is exact.
- Clamp pan so the page edges never leave the viewport once zoomed (compute from container size,
  fitted image size, and zoom).
- Double-tap to toggle 1× ↔ ~2.5× anchored at the tap point (`detectTapGestures(onDoubleTap=...)`
  composed with the transform detector).
- Keep the render-bucket cache (`PageViewer.kt:63-86`) — it's good — but make the cache hold the
  previous bucket's bitmap while the sharper one renders (avoid flashing the spinner mid-zoom;
  today `state = Loading` blanks the page on every bucket change, `PageViewer.kt:77`).
- Move zoom state ownership into the viewer (transient UI state); the ViewModel only needs it if
  zoom must survive process death — it doesn't. Simplifies `DocDetailViewModel.kt:83-89`.

**2b. Page navigation.**
- Replace the single-page `Box` with a **`HorizontalPager`** (commonMain in CMP foundation):
  swipe between pages at 1×; when zoomed in, the transform gesture consumes horizontal drag until
  the pan hits the page edge, then the pager takes over (standard `userScrollEnabled`/nested-scroll
  handling keyed on `zoom > 1f`).
- Prefetch/render page ±1 into the cache so swipes are instant (extend the per-page cache from a
  single `Triple` to a small LRU of ~3 entries).
- Keep the `n / N` pill, but make it tappable → "Go to page" dialog with a number field/slider.
- Desktop input on the pager:
  - Plain mouse wheel: pan vertically when zoomed; page down/up when at 1× (wheel = next/prev page
    is what every PDF reader does for fit-page viewing).
  - **Ctrl+scroll: zoom, anchored at the cursor position** (same `onPointerEvent` +
    `keyboardModifiers.isCtrlPressed` mechanism as the grid — build a small shared helper in
    `ui/common/`, e.g. `Modifier.ctrlScrollZoom(onZoom: (delta, position) -> Unit)`).
  - Keyboard: `←/→` or `PageUp/PageDown` pages, `Home/End` first/last page, `+/-/0` zoom in/out/fit.
    Attach via `Modifier.onKeyEvent` + a `FocusRequester` on the viewer so it has focus by default.
- Fix the zoom buttons: `ZoomOut`/`ZoomIn` icons (see §0) plus a center "fit" button showing the
  current zoom % that resets to 1×.

**2c. Navigation between documents.**
- Add prev/next-document to the detail screen so triaging a stack of scans doesn't require
  round-tripping through the library each time.
- Implementation: the library already holds the sorted, filtered doc list
  (`LibraryViewModel.kt:117-123`). Introduce a small `BrowseContext` (ordered `List<docId>` +
  current index) owned by `AppEnvironment` (or passed as nav argument as ids are stable), populated
  when a doc is opened from the library. `DocDetailScreen` shows:
  - Desktop/wide: chevron buttons at the screen edges + `Ctrl+←/→` (or `n`/`p`) shortcuts, with a
    "3 of 41" indicator in the top bar.
  - Mobile: prev/next icon buttons in the top bar (edge-swipe conflicts with page swiping in the
    pager, so buttons are the safe v1; a vertical-swipe-between-docs experiment can come later).
- Because `DocDetailScreen` keys its VM per doc (`DocDetailScreen.kt:62`), navigating replaces the
  route (`popBackStack` + `navigate`, or `navigate` with `launchSingleTop` on the same route with a
  new id) — no VM redesign needed.
- Respect the review workflow: when the review-queue filter is active, "next" naturally walks the
  queue; combined with the reviewed toggle this becomes a fast triage loop (mark reviewed → next).

Files: `ui/detail/PageViewer.kt` (largely rewritten), `DocDetailScreen.kt`, `DocDetailViewModel.kt`,
`ui/common/` (shared ctrl-scroll/key helpers), `App.kt` (nav), `LibraryViewModel.kt` (browse context).

## 3. Search box: bring filters to the search field

### Today
- Plain `OutlinedTextField` with search + clear icons (`ui/common/SearchField.kt:14-36`).
- Filters live behind a wrench icon in the top bar → `ModalBottomSheet` (`FilterSheet.kt`), with
  active filters echoed as chips below the search field (`ActiveFilters.kt:20-58`).
- Solid foundations (debounced FTS query `LibraryViewModel.kt:105-114`, AND/OR/NOT tag buckets),
  but filtering feels disconnected from searching, and the wrench gives no hint that filters exist
  or are active.

### Target
1. **Move the filter entry point into the search field.** Trailing icon = `FilterList` with a
   count badge when filters are active (`BadgedBox`); tapping opens the existing `FilterSheet`.
   The clear (X) button stays when there's text; both can coexist as two trailing icons. Remove the
   wrench from the top bar (`LibraryScreen.kt:462-464`) — this also decongests the top bar (see §5).
2. **Quick-filter chips row** under the search field (replacing/extending `ActiveFilters`):
   - Always-visible suggestion chips for the highest-value facets: `Tags ▾`, `Type ▾`,
     `Correspondent ▾`, `Needs review` — each opens a small dropdown/popup for that facet alone,
     so common filters take two taps instead of open-sheet → scroll → apply.
   - Active filters render in the same row as selected `InputChip`s with the existing remove
     affordance; "Clear all" chip at the end when ≥2 filters are active.
   - Keep the full `FilterSheet` for the complete picture (and future date-range filters,
     which `PLAN.md` §5 promises but the UI doesn't have yet — add date ranges to the sheet as
     part of this work).
3. **FilterSheet usability fixes** while in there:
   - The OFF→AND→OR→NOT cycling chip (`FilterSheet.kt:75-92,198-209`) is undiscoverable. Keep
     cycling but make state obvious: color-code the chip per bucket (e.g. primary=AND,
     secondary=OR, error-tone=NOT with strikethrough label) instead of a text prefix, and add a
     one-line legend. Alternative if that still confuses: three labeled sections with
     add-tag flows per section.
   - Apply filters live on change (the draft+Apply dance costs a tap and the sheet already shows
     results behind it dimmed); keep "Clear all".
4. **Desktop:** `Ctrl+F` / `/` focuses the search field (`FocusRequester` + key handler at the
   library screen root); `Esc` clears it.
5. Post-v1 idea (note in backlog, don't build now): typed query tokens (`tag:invoice type:receipt`)
   parsed into the same `LibraryFilter` — the chips UI above serves 95% of the need first.

Files: `ui/common/SearchField.kt`, `ui/library/LibraryScreen.kt:289-298`, `ActiveFilters.kt`,
`FilterSheet.kt`, `LibraryViewModel.kt` (facet setters already exist via `setFilter`).

## 4. Sorting UI

### Today
- Trigger icon is `ArrowDropDown` (`LibraryScreen.kt:488-491`) — reads as a generic caret, invisible
  as "sort".
- Menu enumerates 8 items ("Name asc", "Name desc", ... `LibraryScreen.kt:492-509`).
- Sort resets on every app start (only persisted inside saved views).

### Target
1. Icon → `Sort` (or `SwapVert`), see §0.
2. Menu redesign: one item per field (4 items) with a leading radio/check; the active field shows a
   trailing ↑/↓ arrow icon; **tapping the active field again toggles direction** (files-app
   convention). Header row "Sort by". Result: 8 cramped items → 4 self-explanatory ones.
3. Persist `LibrarySort` in `SettingsRepository` (same pattern as `gridColumnWidth`,
   `core/src/{androidMain,desktopMain}/.../SettingsRepositoryImpl.kt`) and restore it in
   `LibraryViewModel` init. Same for `ViewType` (list/grid), which is also lost on restart today.

Files: `ui/library/LibraryScreen.kt:442-513`, `LibraryViewModel.kt:194-196`, both
`SettingsRepositoryImpl`s, `Contracts.kt` (settings keys).

## 5. Other improvements found during the audit

Ordered roughly by value/effort.

1. **View switcher is a dropdown for two options** (`LibraryScreen.kt:466-487`). Replace with a
   single toggle `IconButton` showing the *target* view's icon (grid icon while in list view, list
   icon while in grid). One tap instead of two, one less menu.
2. **Top bar decongestion.** After §3 (filter moves into search) and item 1, the top bar holds
   menu / title / refresh / view-toggle / sort — reasonable. Consider dropping the always-visible
   refresh on Android (pull-to-refresh already exists, `LibraryScreen.kt:308-312`) and keeping it
   desktop-only (+ `F5`/`Ctrl+R`), via a platform flag or window-width check.
3. **Viewer loading flash:** covered in §2a (keep stale bitmap while re-rendering). Also applies
   when toggling between pages that are cached at a different bucket.
4. **Selection top bar overflow on phones** (`LibraryScreen.kt:527+`): four `TextButton`s
   ("Add tag", "Remove tag", "Review", trash) won't fit small screens with a count title. Convert
   to `IconButton`s with tooltips + overflow menu for the rare ones.
5. **Snippet hit highlighting:** `PLAN.md:159` promises highlighting; `DocItems.kt:102-111` renders
   plain italic snippets. FTS5 snippets can emit marker tokens — map them to a bold/primary-colored
   `AnnotatedString` span. Small, high perceived value for search.
6. **Grid card affordances** (`ui/library/DocItems.kt:116-160`): no needs-review indicator in grid
   view (list view has one via `reviewMode`). Add a small badge/dot on the thumbnail so the review
   queue is scannable in grid view too. Also show page count on the thumbnail corner (data is
   available at render time in the detail facade; if it's not on `DocListItem`, skip — don't add
   an index column just for this).
7. **Empty-state actions:** `EmptyState` (`LibraryScreen.kt:313-322`) is text-only. Add a button —
   "Import PDF" when the library is empty, "Clear filters" when a filter matches nothing.
8. **Desktop keyboard map (consolidated):** `Ctrl+F`// focus search, `Ctrl+R` refresh,
   `Ctrl+=/-/0` grid zoom, arrows/PgUp/PgDn/Home/End + `+/-/0` in viewer, `Ctrl+←/→` prev/next
   document, `Esc` back/clear. Implement as small shared helpers in `ui/common/` so shortcuts stay
   consistent and discoverable (list them in Settings > About or a `?` overlay later).
9. **Accessibility pass** alongside the icon sweep: every `IconButton` gets a real
   `contentDescription` (most have them; re-verify after replacing icons), pinch/ctrl-scroll always
   has an on-screen button or menu equivalent (§1.3, §2b zoom buttons ensure this), and the grid
   density control gets semantics (`stateDescription` "N columns").

## 6. Suggested phasing

| Phase | Contents | Size |
|---|---|---|
| **A. Foundations** (done) | §0 icons + sweep; §4 sort menu/icon/persistence; §5.1 view toggle; §5.7 empty states | S |
| **B. Grid zoom** (done) | §1 pinch + ctrl-scroll + buttons, remove settings slider | M |
| **C. Viewer mechanics** | §2a centroid zoom/pan clamp/double-tap; §2b pager, wheel/ctrl-scroll/keys, go-to-page; §5.3 | L |
| **D. Doc-to-doc navigation** | §2c browse context + prev/next + triage loop | M |
| **E. Search & filters** | §3 search-field filter entry + badge, quick chips, sheet fixes, date ranges, `Ctrl+F` | M/L |
| **F. Polish** | §5.4 selection bar, §5.5 highlighting, §5.6 grid badges, §5.8 shortcut map, §5.9 a11y | M |

Each phase is independently shippable; A and B are quick wins that address the two most-felt
complaints (wrong icons, dp slider).

Phase A/B implementation notes (2026-07-18): view-type persistence already existed
(`settings.defaultViewType`), so only sort persistence was added; the `Ctrl+=`/`-`/`0` grid
shortcuts were deferred to the phase-F consolidated shortcut map (they need app-wide focus
handling); the shared gesture helpers live in `ui/common/ZoomGestures.kt` for reuse by the
viewer in phase C. C is the largest single chunk and the riskiest (gesture
interop between transform, tap, pager, and wheel) — budget device testing on Android + a Wayland
and X11 desktop session.

## 7. Testing notes

- Gesture interop is the main regression risk: verify on Android that (a) single-finger scroll of
  the grid never triggers zoom, (b) pinch on the viewer never swipes the pager, (c) pan hand-off to
  the pager at page edges feels right.
- Desktop: Ctrl+scroll must not also scroll; consume the event. Test mouse wheel vs touchpad
  two-finger scroll (touchpads emit fine-grained deltas — accumulate before stepping grid size).
- Unit-testable pieces: pan clamping math, centroid zoom transform, columnWidth step/clamp mapping,
  sort persistence round-trip. Keep them as pure functions in commonMain so they run in plain JVM
  tests.
- Run `detekt` after the icon sweep (unused-import churn).
