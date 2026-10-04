package dev.npn.app.di

import android.content.Context
import androidx.core.net.toUri
import dev.npn.app.platform.hasDirectStorageAccess
import dev.npn.core.app.LibraryLocations
import dev.npn.core.app.SessionSettings
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.app.androidSessionDeps
import dev.npn.core.util.AndroidLog
import dev.npn.core.watch.JvmWatchedFolder
import dev.npn.core.watch.SafWatchedFolder
import dev.npn.core.watch.WatchedFolder
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

/**
 * Android [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a SharedPreferences
 * [SettingsRepositoryImpl]; the per-library wiring itself is `core`'s [androidSessionDeps], so this
 * only checks that a location can be opened and binds the session's settings to the repository.
 */
fun createAppEnvironment(context: Context): AppEnvironment {
    val appContext = context.applicationContext
    val settings = SettingsRepositoryImpl(appContext)
    return DefaultAppEnvironment(
        settings,
        watchedFolder = { location -> runCatching { watchedFolder(appContext, location) }.getOrNull() },
        log = AndroidLog,
    ) { location, scope ->
        checkOpenable(location)
        androidSessionDeps(
            context = appContext,
            location = location,
            scope = scope,
            settings = SessionSettings.from(settings) { Locale.getDefault().language },
            installationId = settings.settings.first().installationId,
        )
    }
}

private fun watchedFolder(
    context: Context,
    location: String,
): WatchedFolder =
    if (LibraryLocations.isContentUri(location)) {
        SafWatchedFolder(context, location.toUri())
    } else {
        JvmWatchedFolder(Paths.get(location))
    }

/**
 * Fail here, with the reason, rather than let the first listing of a direct-storage library come
 * back empty and be read as "every document was deleted": the folder may be on an unmounted SD
 * card, or the all-files grant may have been revoked in system settings since it was chosen.
 */
private fun checkOpenable(location: String) {
    if (LibraryLocations.isContentUri(location)) return
    if (!Files.isDirectory(Paths.get(location))) {
        throw LibraryUnavailableException(OpenFailureReason.FOLDER_MISSING, "Library folder not found: $location")
    }
    if (!hasDirectStorageAccess()) {
        throw LibraryUnavailableException(
            OpenFailureReason.NO_STORAGE_ACCESS,
            "Storage access was revoked; allow \"All files access\" for No Papers Needed to open $location",
        )
    }
}
