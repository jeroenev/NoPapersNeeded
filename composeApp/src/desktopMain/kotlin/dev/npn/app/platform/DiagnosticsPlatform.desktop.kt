package dev.npn.app.platform

import dev.npn.core.app.DesktopLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun platformDescription(): String =
    listOfNotNull(
        System.getProperty("os.name"),
        System.getProperty("os.version"),
        System.getProperty("os.arch")?.let { "($it)" },
    ).joinToString(" ").trim().ifBlank { "Desktop" }

actual fun systemLocaleTag(): String =
    java.util.Locale
        .getDefault()
        .toLanguageTag()

actual suspend fun recentLogTail(maxBytes: Long): String = withContext(Dispatchers.IO) { DesktopLog.tail(maxBytes) }

actual suspend fun shareDiagnosticsBundle(
    fileName: String,
    content: String,
): String = error("Desktop exports the diagnostics file through the save dialog")
