package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import java.util.EnumSet

/** Who took bytes away from a path: deleted them, or wrote other bytes over them. */
enum class Loss {
    /** The app deleted them outside the trash. */
    APP,

    /** The app deleted them in the trash: its trash purge or a delete for good. */
    APP_TRASH,

    /** The sync tool, carrying out what another device's folder did. */
    SYNC,

    /** Another process, a backup put back: the outside world itself. */
    OUTSIDE,
}

/**
 * One time bytes were taken from [path] in a folder: when, by whom, whether [lastHere], no other
 * file there held them, and whether [lastAnywhere], no file in another replica's folder did either.
 */
data class LossAt(
    val atMillis: Long,
    val loss: Loss,
    val path: String,
    val lastHere: Boolean,
    val lastAnywhere: Boolean,
)

/** Every time a folder ([InMemoryLibraryFileSystem]) lost bytes, by their hash. */
class Losses {
    private val byHash = HashMap<String, MutableList<LossAt>>()

    fun record(
        hash: String,
        loss: LossAt,
    ) {
        byHash.getOrPut(hash) { ArrayList() } += loss
    }

    /** How the bytes hashing to [hash] were ever taken, in [Loss] order; empty when they never were. */
    fun of(hash: String): Set<Loss> = byHash[hash].orEmpty().mapTo(EnumSet.noneOf(Loss::class.java)) { it.loss }

    /** Each time the bytes hashing to [hash] were taken, oldest first. */
    fun recordsOf(hash: String): List<LossAt> = byHash[hash].orEmpty().toList()

    /** Each path the bytes hashing to [hash] were taken from, first loss first. */
    fun pathsOf(hash: String): List<String> = byHash[hash].orEmpty().map { it.path }.distinct()

    companion object {
        /**
         * Whether a file at [path] is none of the library's copies, so it neither takes a document's
         * bytes away when it goes nor keeps them while it is there: an import's staging file is the
         * import's copy of its source, and a bake's temporary file holds output no journal names
         * until the bake's relink, which a failed bake discards. Once a relink names them, they are
         * a document's, and the folder counts the temporary file as a copy
         * (`InMemoryLibraryFileSystem.isNoCopy`).
         */
        fun isNoCopy(path: String) = NpnPaths.Temp.kindOf(path) in NO_COPY

        /** Whether [path] is in the trash or the keep store, where the app keeps a document's bytes a while. */
        fun isKept(path: String) = path.startsWith("${NpnPaths.TRASH_DIR}/") || path.startsWith("${NpnPaths.KEEP_DIR}/")

        private val NO_COPY = setOf(NpnPaths.Temp.Kind.IMPORT, NpnPaths.Temp.Kind.BAKE, NpnPaths.Temp.Kind.KEEP_STAGING)
    }
}
