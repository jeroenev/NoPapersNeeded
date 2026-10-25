package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths

/**
 * The names the simulation's outside world makes and the simulation judges by, in one place and
 * derived from what the app knows of them ([NpnPaths], [JournalPaths]), so the simulated sync tool,
 * the in-memory filesystem and the generator cannot drift from the app or from each other.
 */
internal object SimNames {
    /**
     * [tool]'s temporary name for a file it transfers to [path]: beside it, under a name
     * [NpnPaths.isSyncTemp] knows, which is checked here. Syncthing has used two forms; [older]
     * picks its earlier one. Nextcloud's carries a hex [nonce].
     */
    fun syncTemp(
        path: String,
        tool: SyncTool,
        nonce: Int = 0,
        older: Boolean = false,
    ): String {
        val name = NpnPaths.fileName(path)
        val temp =
            when (tool) {
                SyncTool.SYNCTHING -> if (older) "~syncthing~$name.tmp" else ".syncthing.$name.tmp"
                SyncTool.NEXTCLOUD -> ".$name.~${nonce.toString(HEX)}"
                SyncTool.RESILIO -> "$name.!sync"
            }
        check(NpnPaths.isSyncTemp(temp)) { "'$temp' is no sync temporary name the app knows" }
        return beside(path, temp)
    }

    /** Whether [path] is a journal object or snapshot, by the journal's own layout. */
    fun isJournalObject(path: String): Boolean =
        JournalPaths.classify(path).let { it is JournalPath.ObjectV2 || it is JournalPath.SnapshotV2 }

    /**
     * Names the library writes once and never again: a journal object or snapshot, an OCR sidecar,
     * a device marker. Two versions of one under the same name is a broken invariant.
     */
    fun isWriteOnce(path: String): Boolean =
        isJournalObject(path) ||
            (
                path.endsWith(".json") &&
                    (path.startsWith("${NpnPaths.OCR_DIR}/") || path.startsWith("${NpnPaths.DEVICES_DIR}/"))
            )

    private fun beside(
        path: String,
        name: String,
    ): String {
        val dir = NpnPaths.parentDir(path)
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    private const val HEX = 16
}
