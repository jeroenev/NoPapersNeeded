# Privacy policy

_Last updated: 22 November 2026_

No Papers Needed is a document manager for Android, Windows and Linux. This policy explains what happens
to your data when you use it.

## The short version

The app has no account, no server of its own, no analytics, no advertising and no tracking. Your
documents and everything you add to them (tags, fields, notes, rules, settings) stay in the folder
you choose on your device. Nothing is sent to us, because there is no "us" to send it to.

The app only sends anything over the internet in the cases below, each of which you set up or
can switch off.

## What leaves your device, and when

**Cloud sync, if you set it up.** When you connect a cloud provider (for example Google Drive,
Dropbox, OneDrive, a WebDAV or Nextcloud server, S3 or SFTP), the app copies your library folder
to the storage you picked and back. The sign-in for that provider happens on the provider's own
website, and the app keeps the resulting access token on your device. What the provider does
with your files is covered by that provider's privacy policy.

**The AI assistant, if you set it up.** The assistant is off until you enter an AI provider of
your choice (such as OpenAI, Anthropic, Google, Mistral, or a server you run yourself). When you
use it, the app sends that provider the text of the documents involved and your question, or the
page images for handwriting and table reading. The app asks before sending documents, and the
provider's own privacy policy applies to what it receives. Your API key is kept with the app's
settings on your device.

**Document scanning on Android.** The camera scanner is Google's ML Kit document scanner, which
runs on your device through Google Play services. Your scans are not uploaded, but Google may
collect device and usage information about the scanner to keep it working, as described in
[ML Kit's terms](https://developers.google.com/ml-kit/terms). Text recognition runs entirely on
your device.

**Checking for a new version.** If you installed the app outside an app store (the APK or a Linux
package from the releases page), it can look up the latest release on GitHub once per start. This
sends GitHub an ordinary request, which includes your IP address, and nothing about your
documents. You can turn it off in Settings → About. App store installs (Google Play, Flathub) do
not make this request; the store handles updates.

**Android's own backup.** The app keeps its settings (including an API key), cloud sign-ins and
assistant chats out of the device backup Android makes to your Google account and out of a
transfer to a new phone, so you set those up again there. Your library folder is not part of the
backup either; it is an ordinary folder you can copy or sync yourself.

## Permissions

On Android the app asks for "All files access" so that your library can be an ordinary folder,
which sync tools and other apps can also read and write. The app only reads and writes inside the
library folder you pick and the inbox folders you choose to watch. The camera is used only while
you scan.

## Children

The app is not directed at children and collects no data from anyone.

## Your data, your control

Because your documents and settings live in your own folder, you can see, copy or delete them at
any time with any file manager. Uninstalling the app removes its local search index and settings;
your library folder stays where it is until you delete it.

## Changes and contact

Changes to this policy are published in this file, with a new date at the top. For questions,
open an issue at <https://github.com/jeroenev/NoPapersNeeded/issues>.
