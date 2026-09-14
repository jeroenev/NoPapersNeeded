package dev.npn.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths

/** Creating library folders on a plain filesystem (desktop, Android direct storage). */
object LocalLibraryFolders {
    /**
     * Create `parent/name` (and any missing ancestors) and return its absolute path, or null when
     * the name escapes [parent] or the directory cannot be created. An existing directory is
     * returned as is, so the platform's recommended default folder is opened through this too.
     */
    suspend fun create(
        parent: String,
        name: String,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = name.filterNot { it == '/' || it == '\\' || it == '\u0000' }.ifBlank { "library" }
                val parentPath = Paths.get(parent).toAbsolutePath().normalize()
                val target = parentPath.resolve(safeName).normalize()
                // Reject anything that escapes the chosen parent (e.g. a name of "..").
                if (target == parentPath || !target.startsWith(parentPath)) return@runCatching null
                Files.createDirectories(target)
                target.toString()
            }.getOrNull()
        }
}
