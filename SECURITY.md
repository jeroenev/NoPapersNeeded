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
