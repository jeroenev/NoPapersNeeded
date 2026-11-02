package dev.npn.app.platform

/**
 * Whether the shell offers the keyboard shortcut list (`?`, Ctrl+/ and an entry in the navigation):
 * on desktop, where the keyboard is how the app is driven, and not on a phone, where nearly no one
 * has one attached.
 */
expect fun offersKeyboardShortcutList(): Boolean
