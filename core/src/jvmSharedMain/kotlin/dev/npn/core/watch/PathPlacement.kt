package dev.npn.core.watch

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether [folder] is [library] or a folder below it, compared by name elements as real paths: with
 * symlinks resolved, and a part not there yet as written below its nearest existing parent. Blocks
 * on the filesystem.
 */
internal fun pathLiesIn(
    folder: Path,
    library: Path,
): Boolean = folder.real().startsWith(library.real())

private fun Path.real(): Path {
    val absolute = toAbsolutePath().normalize()
    var existing: Path? = absolute
    while (existing != null && !Files.exists(existing)) existing = existing.parent
    if (existing == null) return absolute
    val real = runCatching { existing.toRealPath() }.getOrDefault(existing)
    return real.resolve(existing.relativize(absolute))
}
