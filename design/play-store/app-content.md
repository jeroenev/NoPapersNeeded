# Play Console: App content answers (draft)

Draft answers for Policy and programmes → App content. Check each against the form before
submitting; the questions change now and then.

## Privacy policy

`https://github.com/jeroenev/NoPapersNeeded/blob/main/PRIVACY.md` (live once the repo is public).

## Ads

No, the app contains no ads.

## App access

All functionality is available without special access: there is no sign-in. (The AI assistant and
cloud sync need the reviewer's own provider; say so in the notes, nothing else is gated.)

## Content rating (IARC questionnaire)

Category: Utility, productivity, communication or other. Every content question (violence,
sexuality, language, controlled substances, gambling) is No. Users do not interact with each
other, share location or buy digital goods. Expected rating: Everyone / PEGI 3.

## Target audience

18 and over (simplest: no Families policy obligations). Not designed to appeal to children.

## News app / COVID-19 / government / financial features / health

No / not applicable / no / none / no.

## Data safety

Google counts any transfer off the device as "collection", even when the data never reaches us,
so the answers declare what the opt-in features send, as optional and for app functionality.

**Does your app collect or share any of the required user data types?** Yes.

**Is all of the user data collected by your app encrypted in transit?** No. Cloud providers and
hosted AI providers are HTTPS, but the app deliberately allows plain HTTP to an AI server or WebDAV
server the user enters (Ollama on the LAN, for example), so the honest answer is No.

**Do you provide a way for users to request that their data is deleted?** The developer holds no
user data. Answer that data is deleted by the user: it lives in their own folder, and in their own
cloud or AI provider account.

| Data type | Collected | Shared | Optional | Purpose | Why |
|---|---|---|---|---|---|
| Files and docs | Yes | No | Yes | App functionality | Cloud sync copies the library to the user's chosen storage; the AI assistant sends document text to the user's chosen provider |
| Photos | Yes | No | Yes | App functionality | The AI assistant can send page images for handwriting and table reading |
| App info and performance → Diagnostics | Yes | No | No | Analytics | ML Kit (document scanner, text recognition) reports diagnostics to Google |
| Device or other IDs | Yes | No | No | Analytics | ML Kit's per-installation identifier for the same diagnostics |

"Shared" is No for the first two because sending data to a third party at the user's own request
is exempt from sharing. Check the ML Kit rows against Google's "Data disclosure" section of the
[ML Kit document scanner](https://developers.google.com/ml-kit/vision/doc-scanner) and
[text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) pages;
mark them "processed ephemerally" only if that page says so.

Not collected: name, email, contacts, location, financial info, messages, audio, calendar, web
history, app activity, health.

## Permissions declaration: All files access (`MANAGE_EXTERNAL_STORAGE`)

- **Core use case:** Document management.
- **Why the app needs it:** The library is a plain folder on shared storage
  (`Documents/NoPapersNeeded` by default) that the app reads and writes directly, so sync tools such
  as Syncthing can share it with the user's other devices. Scoped storage only shows an app the
  files it created itself; seeing the PDFs a sync tool or another app writes into that folder
  requires All files access. Libraries on cloud document providers go through the Storage Access
  Framework instead and need no permission.
- **Video:** record picking a library folder on first run, the system "All files access" switch,
  and a file added by a sync tool appearing in the library.
