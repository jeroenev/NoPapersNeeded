# Homepage

The public homepage, published to <https://jeroenev.github.io/NoPapersNeeded/> by
`.github/workflows/pages.yml` on every push to `main` that touches this folder or
`design/screenshots/`.

It is plain static HTML, CSS and SVG: no Jekyll, no generator, no build step. GitHub Pages
serves the artifact the workflow uploads, with **Settings → Pages → Source** set to
*GitHub Actions*.

## Files

| File | What it is |
|---|---|
| `index.html` | The whole page; one hand-written file |
| `styles.css` | The stylesheet, including a `prefers-color-scheme: dark` variant |
| `icon.svg` | The app icon, copied from `design/branding/icon.svg` |

## Screenshots

The images come from `design/screenshots/`, the stills `refreshing-screenshots` renders, and the
deploy copies them into `screenshots/` (see `.github/scripts/assemble-site.sh`). They are not
duplicated here, so retaking a shot needs no homepage change: `index.html` refers to
`screenshots/<name>.png`, and a new shot means adding its markup.

## Preview locally

```sh
mise pages-preview
```

That assembles the same staging directory the workflow deploys and serves it on
<http://localhost:8080>, so the relative `screenshots/` paths resolve exactly as they do in
production. `Ctrl+C` stops the server; the staging directory is `build/pages-site/` and is
ignored by Git.
