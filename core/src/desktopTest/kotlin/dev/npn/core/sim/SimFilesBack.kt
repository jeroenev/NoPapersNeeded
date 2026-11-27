package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.sameAs
import dev.npn.core.journal.FileStat
import dev.npn.core.journal.FoldedState

/**
 * The files in [fs] of trashed documents the last rescan [reported] back with the stat their trash
 * took them with (`ScanReport.filesBack`): each is the user's to restore, and stays in the trash
 * until then. A document can have several (a move and a trash on two devices at once leave a copy
 * at each path). Each reported document must have one: [fold] has it in the trash with a recorded
 * stat a file holds its bytes with, or the report named it wrongly, which fails the result.
 */
internal fun filesBackWithTheirTrashStat(
    fs: InMemoryLibraryFileSystem,
    fold: FoldedState,
    reported: Set<String>,
): Result<Set<String>> {
    val back =
        fs.allFiles().filter { (path, bytes) ->
            val docId = sha256Bytes(bytes)
            val recorded = fold.trashStat(docId)
            NpnPaths.isDocumentPath(path) && docId in reported && recorded != null &&
                fs.statAt(path)!!.let { FileStat(it.size, it.mtimeMillis) }.sameAs(recorded)
        }
    val unbacked = reported.firstOrNull { docId -> back.values.none { sha256Bytes(it) == docId } }
    return if (unbacked != null) {
        Result.failure(
            IllegalStateException(
                "the last scan reports ${unbacked.take(ID_CHARS)} back, but no file holds it with its trash's stat",
            ),
        )
    } else {
        Result.success(back.keys)
    }
}

private const val ID_CHARS = 8
