package dev.npn.app.di

import android.content.Context
import dev.npn.app.platform.hasDirectStorageAccess
import dev.npn.app.platform.isAppPrivateLocation
import dev.npn.core.app.androidRclone
import dev.npn.core.app.androidSessionDeps
import dev.npn.core.settings.LibraryLocations
import dev.npn.core.settings.SessionSettings
import dev.npn.core.settings.SettingsRepositoryImpl
import dev.npn.core.sync.RcloneTransport
import dev.npn.core.util.AndroidLog
import dev.npn.core.watch.androidWatchedFolder
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Paths

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
        watchedFolder = { location -> androidWatchedFolder(appContext, location) },
        log = AndroidLog,
        cloudSetup = { RcloneTransport(androidRclone(appContext)).let { CloudSetupEngine(it.setup, it::close) } },
    ) { location, scope ->
        checkOpenable(location)
        androidSessionDeps(
            context = appContext,
            location = location,
            scope = scope,
            settings = SessionSettings.from(settings),
            installationId = settings.settings.first().installationId,
        )
    }
}

/**
 * Fail here, with the reason, rather than let the first listing of a direct-storage library come
 * back empty and be read as "every document was deleted": the folder may be on an unmounted SD
 * card, or the all-files grant may have been revoked in system settings since it was chosen. A
 * cloud library's working copy is in the app's own storage, which needs no grant.
 */
private fun checkOpenable(location: String) {
    if (LibraryLocations.isContentUri(location)) return
    if (!Files.isDirectory(Paths.get(location))) {
        throw LibraryUnavailableException.folderMissing(location)
    }
    if (!isAppPrivateLocation(location) && !hasDirectStorageAccess()) {
        throw LibraryUnavailableException(
            OpenFailureReason.NO_STORAGE_ACCESS,
            "Storage access was revoked; allow \"All files access\" for No Papers Needed to open $location",
        )
    }
}
