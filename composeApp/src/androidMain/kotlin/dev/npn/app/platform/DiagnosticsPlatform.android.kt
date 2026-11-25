package dev.npn.app.platform

import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.SystemEpochClock
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

actual fun platformDescription(): String =
    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) on ${Build.MANUFACTURER} ${Build.MODEL}"

actual fun systemLocaleTag(): String =
    java.util.Locale
        .getDefault()
        .toLanguageTag()

actual suspend fun recentLogTail(
    maxBytes: Long,
    io: CoroutineDispatcher,
): String =
    withContext(io) {
        AndroidLog.install(FileKit.context.applicationContext)
        AndroidLog.tail(maxBytes)
    }

actual suspend fun shareDiagnosticsBundle(
    fileName: String,
    content: String,
    io: CoroutineDispatcher,
): String {
    val context = FileKit.context.applicationContext
    val file =
        withContext(io) {
            writeShareCopy(
                shareDir = File(context.cacheDir, "share"),
                fileName = fileName,
                bytes = content.encodeToByteArray(),
                nowMillis = SystemEpochClock.nowMillis(),
            )
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    LockActivityHandoff.started()
    try {
        context.startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: Exception) {
        LockActivityHandoff.clear()
        throw failure
    }
    return file.absolutePath
}
