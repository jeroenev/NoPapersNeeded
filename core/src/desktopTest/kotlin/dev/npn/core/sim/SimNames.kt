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

    /**
     * Whether [path] is a document name the app invents beside a taken one, by the suffix it adds:
     * a hash token (`name (7f3a).pdf`, `(part 1, 7f3a)`, `(restored, 7f3a)`), or the counters it
     * added before those (`(2)`, `(part 1)`, `(restored)`, `(restored 1)`). `(1)` is no counter the
     * app adds, and the simulation imports a `scan (1).pdf`; a storage path template's name is the
     * template's, not invented.
     */
    fun isInvented(path: String): Boolean =
        !path.startsWith("${NpnPaths.NPN_DIR}/") &&
            INVENTED.containsMatchIn(NpnPaths.stripPdfExtension(NpnPaths.fileName(path)))

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

    /**
     * The hash token in an [isInvented] name (`7f3a` of `name (7f3a).pdf`, `(part 1, 7f3a)`,
     * `(restored, 7f3a)`), or null for a counter: the bytes the app named it for start with it.
     */
    fun nameToken(path: String): String? =
        TOKEN.find(NpnPaths.stripPdfExtension(NpnPaths.fileName(path)))?.groupValues?.get(1)

    private const val HEX = 16

    private val TOKEN = Regex(""" \((?:part \d+, |restored(?: \d+)?, )?([0-9a-f]{4})\)""")

    /** The suffixes of [isInvented]. */
    private val INVENTED =
        Regex(""" \((?:[2-9]|[1-9]\d+|[0-9a-f]{4}|part \d+(?:, [0-9a-f]{4})?|restored(?: \d+)?(?:, [0-9a-f]{4})?)\)$""")
}
