package dev.npn.app.platform

/**
 * The OS and device a diagnostics bundle names, in the user's terms: "Linux 6.8.0 (amd64)" or
 * "Android 14 (SDK 34) on Pixel 8". Never a hostname, account or path.
 */
expect fun platformDescription(): String

/** The platform's language tag, e.g. "en-US"; used when the app follows the system language. */
expect fun systemLocaleTag(): String

/**
 * The recent app log, for the diagnostics bundle. Desktop reads `npn.log`; Android reads the
 * size-capped file under `filesDir`. Empty when the platform keeps none.
 */
expect suspend fun recentLogTail(maxBytes: Long = 64L * 1024): String

/**
 * Write the diagnostics text under Android's cache and hand it to the share sheet, returning the
 * absolute path shown to the user. Only called where [supportsSharing] is true; desktop exports
 * through the save-file dialog instead.
 */
expect suspend fun shareDiagnosticsBundle(
    fileName: String,
    content: String,
): String
