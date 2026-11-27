package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import kotlin.random.Random

/**
 * The user copies trashed [doc]'s file back where the library had it, from the trash or anywhere else
 * in [fs] it is: keeping its mtime, as [random] picks (a file manager's copy, `cp -p`), which leaves it in
 * the trash until restored, or with a new one (`cp`), a return the next rescan records. A path that
 * holds a file already is left alone, as that is an overwrite's to model.
 */
internal fun putBackTrashed(
    fs: InMemoryLibraryFileSystem,
    doc: SimDoc,
    random: Random,
): Planned.Control {
    val keepMtime = random.nextBoolean()
    val how = if (keepMtime) "keeping its mtime" else "with a new mtime"
    return Planned.Control(
        "external: put trashed ${doc.docId.take(ID_CHARS)}@'${doc.relPath}' back $how",
        instant = true,
    ) {
        val copy =
            fs
                .allFiles()
                .entries
                .firstOrNull { (path, bytes) -> path != doc.relPath && sha256Bytes(bytes) == doc.docId }
        when {
            !NpnPaths.isDocumentPath(doc.relPath) || fs.bytesAt(doc.relPath) != null -> {
                "its path is taken"
            }

            copy == null -> {
                "no copy of it here"
            }

            else -> {
                val mtime = if (keepMtime) fs.statAt(copy.key)?.mtimeMillis else null
                if (mtime != null) {
                    fs.externalWriteAtomic(doc.relPath, copy.value, mtime)
                } else {
                    fs.externalWriteAtomic(doc.relPath, copy.value)
                }
                "from '${copy.key}'"
            }
        }
    }
}

private const val ID_CHARS = 8
