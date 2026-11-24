# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue: use
[Report a vulnerability](https://github.com/jeroenev/NoPapersNeeded/security/advisories/new)
on the repository's Security tab.

Include what you found, the version and platform (Android or desktop), and the steps to
reproduce it. You will get an answer within a week. Once a fix is released, the advisory is
published with credit to you, unless you'd rather stay anonymous.

## Supported versions

Only the latest release gets security fixes.

## Scope

In scope: the Android app, the desktop app and its packages (AppImage, Flatpak, deb, rpm,
Windows installers), and the release workflows in this repository.

Out of scope: problems in the cloud providers, AI endpoints or sync tools you connect the app
to, and in the rclone and Tesseract releases it bundles; report those upstream. The debug
signing key in `androidApp/npn-debug.keystore` is public on purpose and only signs debug builds,
never a release.

## App lock

The optional app lock (Settings → Security) is access control over the app's screens, **not
encryption**. The library folder stays plain PDFs and JSON, so anyone with access to those files
can read them; the lock does not claim otherwise, and its Settings copy says so. Only a salted
PBKDF2-HMAC-SHA256 hash of the PIN is stored, in this device's settings (`settings.properties` on
desktop, `npn_settings` on Android), never the PIN itself.

Recovery, so a forgotten PIN never locks a user out of their own files:

- Android also accepts the device's biometrics or screen lock, with the device credential always
  among the allowed authenticators.
- On desktop, close the app and delete the `appLock*` entries from `settings.properties` (or the
  file, which resets every device setting), as the Security pane and the lock screen say.
- On Android, clearing the app's data removes the lock along with everything the app keeps only
  on this device: its settings, `device.db` (assistant chats, queues), the cloud connection, and a
  cloud library's working copy, so changes not yet synced are lost. A library folder in shared
  storage, and its journal, are untouched.
