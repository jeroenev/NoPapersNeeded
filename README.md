# No papers needed

**Your paperwork, organized. Your files, still yours.**

No papers needed turns the pile of bills, contracts, receipts and letters into a
searchable library on your phone and your computer. Scan a letter, drop in a
download, and find it again in seconds by typing a word from the page.

There's no account to create and no server to run. Your documents stay ordinary PDFs in
a folder you choose, readable by any other app, today and in ten years.

And if you set up an AI assistant, it can file new arrivals for you, answer questions
about your paperwork and read scans that regular text recognition cannot.

![Your library at a glance](design/screenshots/library.png)

## Why you'll like it

- **Find anything.** Search the full text of every document, scans included. Text
  recognition runs in the background, so a photo of a letter becomes searchable too.
- **Scan in seconds.** On Android, point your camera at a page and the scanner crops
  and straightens it. Share a PDF or photo from any app straight into your library.
- **Drag, drop, done.** On the desktop, drag files into the window or let the app watch
  an inbox folder and pick up whatever lands there.
- **Organize your way.** Tags (nested if you like), correspondents, document types,
  dates, notes, and fields you define yourself. Rules can tag new documents
  automatically based on their text.
- **Stay on top of new arrivals.** A review queue shows what still needs sorting, and
  saved views keep your favorite filters one tap away.
- **Let the assistant do the filing.** Connect an AI provider of your choice and it
  proposes tags, titles, correspondents and dates for a whole batch at once — the inbox,
  everything untagged, a saved view or a selection. You review the suggestions before
  anything is saved.
- **Ask your paperwork anything.** "When does my car insurance renew?" The assistant
  searches your library, reads the documents it finds and answers from them, showing
  which ones it used. It also reads handwriting, tables and faint scans that on-device
  text recognition misses.
- **Every device, one library.** Keep the folder in sync with the tool you already use,
  such as Syncthing or Nextcloud, and every phone and computer sees the same documents,
  tags and search results. Changes made on two devices at once merge cleanly.
- **Or sync it to your cloud from the app.** Mirror the library to Google Drive, Dropbox,
  OneDrive, WebDAV/Nextcloud, S3, Backblaze B2, SFTP and more, set up entirely in the app:
  pick a provider, sign in and choose a folder. The files stay plain files there too, and
  nothing else needs installing.
- **No lock-in.** Your files are never converted, renamed behind your back or locked in
  a database. Stop using the app and you still have a tidy folder of PDFs.
- **Safe by design.** Deleted documents go to a trash you can restore from, and the app
  spots duplicates and files that went missing.

## A closer look

| | |
|---|---|
| ![Search finds words inside your documents](design/screenshots/search.png) | ![Read a document right next to your library](design/screenshots/preview.png) |
| **Search** finds the words inside your documents, not just their names. | **Preview** a document right next to your library. |
| ![Title, correspondent, type, date and tags](design/screenshots/details.png) | ![Filter by tags, correspondent, type and more](design/screenshots/filters.png) |
| **Details** such as correspondent, type and date, plus fields of your own. | **Filters** narrow the library down by tags, correspondent, type or any field. |

### On your phone

The same library, laid out for a small screen.

<p>
  <img src="design/screenshots/phone-library.png" alt="Library on a phone" width="250">
  <img src="design/screenshots/phone-details.png" alt="Document details on a phone" width="250">
  <img src="design/screenshots/phone-tags.png" alt="Tags on a phone" width="250">
</p>

## Optional AI assistant

Set it up with a provider of your choice — OpenAI, Anthropic, Google, Mistral, DeepSeek,
OpenRouter, Ollama on your own machine, or any other OpenAI-compatible server — using
your own key. There is no account with us and no server of ours in between: requests go
from your device straight to that provider.

- **Ask.** Questions in plain words; each conversation is kept on this device, so you
  can pick it up again later.
- **Bulk edit.** Have it propose tags, titles, correspondents and dates across the inbox,
  everything untagged, a saved view or a selection. Every suggestion is shown first, and
  long runs can keep working in the background for you to review later.
- **Read the hard pages.** Handwriting, tables and faint scans come out as searchable
  text that on-device recognition struggles with.
- **File as you go.** With auto-organize on, each new document gets tags from the ones you
  already use and any field you have marked for the assistant to fill.

Off by default. Bulk edits change nothing until you approve them, and auto-organize is
the one path that applies its suggestions as documents arrive. The app warns you before a
run sends a large batch, since that is one request per document to your provider.

## Get it

Download the latest version from the
[releases page](https://github.com/jeroenev/NoPapersNeeded/releases):

- **Android** 11 or newer: the `.apk`.
- **Linux**: AppImage, Flatpak, `.deb` or `.rpm`. Everything needed to run it is
  included. For text recognition on the desktop, install `tesseract-ocr` (the Flatpak
  already includes it).

The app tells you when a new version is out.

Available in English, Dutch, German, Spanish, French and Portuguese.

## For developers

Building from source, packaging, releases and the library format are covered in
[DEVELOPMENT.md](DEVELOPMENT.md). The code layout is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Privacy

No account, no analytics, no tracking. The details are in [PRIVACY.md](PRIVACY.md).

## License

Apache-2.0. See [LICENSE](LICENSE).
