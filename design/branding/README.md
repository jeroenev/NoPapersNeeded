# App identity proposal

## Recommendation: Folnook

**Folder + nook:** a small, personal home for documents. Pronounce it **FOHL-nook**.
Suggested tagline: **Your documents. At home.**

The icon is a folded document held in an open folder. This reflects the actual
product: ordinary PDFs in a folder the user owns. Its large shapes and warm palette
give a practical document utility a more welcoming character.

## Artwork

- `icon.svg`: editable 512 × 512 full-colour icon, rounded-square presentation.
  This is the source of truth for every shipped icon.
- `icon-monochrome.svg`: transparent, single-colour mark with negative-space text lines.
- `preview.html`: self-contained local presentation with inline SVG, small-size
  samples, a circular mask study, colours, and candidate names. Open in a browser.

Palette: deep teal `#123F43`, ivory `#FFF7E8`, apricot `#FFB77E`, sage
`#78AAA3`, and light sage `#BBD4CB` for the folded corner. Artwork uses vector paths
and contains no font dependencies or external assets.

## Where the app uses it

| Target | Files | How |
| --- | --- | --- |
| Android | `androidApp/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `values/ic_launcher_background.xml`, `mipmap-anydpi/ic_launcher.xml` | Adaptive icon: teal background layer, the mark as the foreground, plus a monochrome layer for Android 13+ themed icons. Hand-ported vector drawables — no rendering step. Every supported device (minSdk 30) reads adaptive icons, so no density PNG fallbacks are needed. |
| Desktop | `desktopApp/src/main/resources/icon.png`, `icon.ico` | The PNG is the Compose window icon, the jpackage `.deb` icon, and the AppImage AppDir icon. The `.ico` is the jpackage Windows icon. Both are committed and generated. |

A window icon alone is not enough on Linux: shells such as GNOME identify a window by
its `WM_CLASS` and take the taskbar icon from the matching `.desktop` entry. AWT derives
`WM_CLASS` from the main class (`dev.npn.desktop.MainKt` becomes `dev-npn-desktop-MainKt`,
and the JDK offers no way to override it), so both the `.deb` and the AppImage declare
that string as `StartupWMClass`. Running from source with `mise desktop` has no installed
entry to match, so the taskbar shows a generic icon there until the app is installed.

The vector drawables scale the 512-unit artwork by `0.2103` into the 108 dp adaptive
canvas, which keeps the mark inside the 72 dp safe circle under any launcher mask. If
the SVG paths change, update both drawables to match; they are copies, not imports.

### Regenerating the desktop bitmaps

```
mise icons
```

That task (`.mise/tasks/icons`) renders `icon.svg` with `resvg` and packs the
multi-resolution `.ico` with Pillow via `uv`; mise provisions both tools. Nothing in
the Gradle build depends on them, so a normal build never needs them installed.

Verified: the Android adaptive icon is present in the debug APK, and both the `.deb`
and the AppImage ship the PNG with a desktop entry carrying `StartupWMClass`. The
Windows `.ico` path has not been built or checked on Windows.

## Name screening — 9 September 2026

Public web searches covered each exact name, app/software queries, and a grouped
site-restricted query covering Google Play, Apple App Store, GitHub, Flathub, and
F-Droid. This checks search-engine-indexed results, not every store's internal
catalogue or every region. No indexed app matches were found for the three names
below. That is a preliminary finding, not proof that a name has never been used;
domain and trademark availability were not established.

| Name | Rationale | Search findings / tradeoff |
| --- | --- | --- |
| **Folnook** | Folder + nook; strongest connection to a personal document home. | Exact-name results were unrelated/noisy social-page text. No indexed app or software product match found. Coined spelling needs a descriptive subtitle at launch. |
| **Folnora** | Soft, folio-inspired invented name; FOHL-nor-ah. | No indexed app match found. Other results included a game-character name and historical/OCR text. Search also surfaced the similar name **Folnoria**, a hair-loss product, so search distinctiveness is weaker. |
| **Folvika** | Folder-inspired, Nordic feel; fol-VEE-kah. | No indexed app match found. Existing place/hospitality use: **Følvika Northern Retreat**, Norway. Less distinctive outside software. |

Reproducible query patterns (substitute each candidate):

```text
"Folnook"
"Folnook" (site:play.google.com OR site:apps.apple.com OR site:github.com OR site:flathub.org OR site:f-droid.org)
```

### Rejected candidates and evidence

- **LeafKeep** is already a plant-care app:
  <https://apps.apple.com/us/app/leafkeep/id6789780774>.
  There is also a reading product at <https://leafkeep.net/>.
- **PaperNook** is already used for software, including a local-first notes app
  at <https://papernook.app/> and a filesystem-first paper library at
  <https://github.com/affromero/papernook>.
- **Ownfolio** is an existing portfolio analytics product:
  <https://www.ownfolio.net/>.
- **Paprivo** has an existing stationery/printables shop, making it a poor
  document-brand candidate: <https://www.etsy.com/de/shop/Paprivo>.
- **Folmori** searches prominently returned **Filmora**, making it a weak
  choice for spelling and search discoverability.

Other relevant existing uses:

- Følvika Northern Retreat: <https://www.visitnorway.it/listings/folvika-northern-retreat/211826/>.
- Similar spelling Folnoria: <https://folnoria.com/>.
