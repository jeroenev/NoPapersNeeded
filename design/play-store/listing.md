# Google Play store listing

Text and graphics for the No Papers Needed entry in Play Console
(package `io.github.jeroenev.NoPapersNeeded`). The graphics are rendered from `src/` with
resvg (`resvg --resources-dir src src/<name>.svg <name>.png`); the screenshots are
`design/screenshots/*.png` centred on a teal canvas, since Play takes only 9:16 (phone) or 16:9
(tablet) screenshots.

| Asset | File | Play's requirement |
|---|---|---|
| App icon | `icon-512.png` | 512 × 512 PNG; Play rounds the corners itself, so it is full-bleed |
| Feature graphic | `feature-graphic.png` | 1024 × 500 |
| Phone screenshots | `phone-library.png`, `phone-details.png`, `phone-tags.png` | 2–8, 1080 × 1920 (9:16) |
| Tablet screenshots | `tablet-library.png`, `tablet-details.png`, `tablet-filters.png`, `tablet-preview.png` | 2560 × 1440 (16:9); optional |

## App name (30)

No Papers Needed

## Short description (80)

Scan, search and organize your paperwork as plain PDFs in a folder you own.

## Full description (4000)

Your paperwork, organized. Your files, still yours.

No Papers Needed turns the pile of bills, contracts, receipts and letters into a searchable library on your phone and your computer. Scan a letter, share in a download, and find it again in seconds by typing a word from the page.

There's no account to create and no server to run. Your documents stay ordinary PDFs in a folder you choose, readable by any other app, today and in ten years.

WHY YOU'LL LIKE IT

• Find anything. Search the full text of every document, scans included. Text recognition runs on your device in the background, so a photo of a letter becomes searchable too.
• Scan in seconds. Point your camera at a page and the scanner crops and straightens it. Share a PDF or photo from any app straight into your library.
• Organize your way. Tags (nested if you like), correspondents, document types, dates, notes, and fields you define yourself. Rules can tag new documents automatically based on their text.
• Stay on top of new arrivals. A review queue shows what still needs sorting, and saved views keep your favorite filters one tap away.
• Every device, one library. Keep the folder in sync with the tool you already use, such as Syncthing or Nextcloud, and every phone and computer sees the same documents, tags and search results. Changes made on two devices at once merge cleanly.
• Or sync it to your cloud from the app. Mirror the library to Google Drive, Dropbox, OneDrive, WebDAV/Nextcloud, S3, Backblaze B2, SFTP and more, set up entirely in the app: pick a provider, sign in and choose a folder. The files stay plain files there too.
• No lock-in. Your files are never converted, renamed behind your back or locked in a database. Stop using the app and you still have a tidy folder of PDFs.
• Safe by design. Deleted documents go to a trash you can restore from, and the app spots duplicates and files that went missing.

OPTIONAL AI ASSISTANT

Connect an AI provider of your choice — OpenAI, Anthropic, Google, Mistral, DeepSeek, OpenRouter, Ollama on your own machine, or any other OpenAI-compatible server — using your own key. There is no account with us and no server of ours in between: requests go from your device straight to that provider.

• Ask. "When does my car insurance renew?" The assistant searches your library, reads the documents it finds and answers from them, showing which ones it used.
• Bulk edit. Have it propose tags, titles, correspondents and dates across the inbox, everything untagged, a saved view or a selection. Every suggestion is shown before anything is saved.
• Read the hard pages. Handwriting, tables and faint scans come out as searchable text that on-device recognition struggles with.
• File as you go. With auto-organize on, each new document gets tags from the ones you already use and any field you have marked for the assistant to fill.

It's off until you set it up, and it asks before anything that sends documents to your provider. Bulk edits change nothing until you approve them; auto-organize is the one path that applies its suggestions as documents arrive.

ALSO ON WINDOWS AND LINUX

The same library opens in the desktop apps for Windows (an .msi or .exe installer) and Linux (Flatpak, AppImage, .deb or .rpm).

WHY "ALL FILES ACCESS"

Your library is a normal folder on your phone, so that sync tools and other apps can read and write the same PDFs. Android only lets an app keep working in such a folder with the "All files access" permission. No Papers Needed only reads and writes inside the library folder you pick and the inbox folders you choose to watch.

Available in English, Dutch, German, Spanish, French and Portuguese. Open source under the Apache 2.0 license.

## Category and contact

- Category: Productivity
- Tags: Business, House & home, Privacy & security, Productivity, Tools (Play has no document or file manager tag)
- Contact email: usual-filter-even@duck.com
- Website: https://github.com/jeroenev/NoPapersNeeded
