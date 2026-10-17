package dev.npn.core.watch

import android.content.Context
import androidx.core.net.toUri
import dev.npn.core.app.LibraryLocations
import java.nio.file.Paths

/** The watched folder a persisted Android [location] names: a SAF tree uri, or a plain path. */
fun androidWatchedFolder(
    context: Context,
    location: String,
): WatchedFolder =
    if (LibraryLocations.isContentUri(location)) {
        SafWatchedFolder(context, location.toUri())
    } else {
        JvmWatchedFolder(Paths.get(location))
    }
