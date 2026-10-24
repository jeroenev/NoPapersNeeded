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
 * One time bytes were taken from [path] in a folder: when, by whom, and whether [lastHere], no other
 * file there held them.
 */
data class LossAt(
    val atMillis: Long,
    val loss: Loss,
    val path: String,
    val lastHere: Boolean,
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
        /** Whether [path] is an import's staging file, the import's copy of its source. */
        fun isImportStaging(path: String) = NpnPaths.Temp.kindOf(path) == NpnPaths.Temp.Kind.IMPORT
    }
}

/**
 * Who last put bytes at a path and when: the app through one of its calls, or the outside world
 * (another process, a backup put back, the sync tool, which all go through the `external*` members
 * of [InMemoryLibraryFileSystem]). A touch puts no bytes anywhere, and a delete leaves the entry as
 * it was.
 */
data class LastWrite(
    val atMillis: Long,
    val byApp: Boolean,
    /** The hash of the bytes that write left there. */
    val hash: String,
)

/** Who wrote at each path of a folder, and when the app last took bytes away from one. */
class PathWrites {
    private val lastWrites = HashMap<String, LastWrite>()

    // When the app last moved or deleted the bytes of a hash away from a path, by path and hash.
    private val appTookAway = HashMap<Pair<String, String>, Long>()

    fun wrote(
        path: String,
        write: LastWrite,
    ) {
        lastWrites[path] = write
    }

    fun tookAway(
        path: String,
        hash: String,
        atMillis: Long,
    ) {
        appTookAway[path to hash] = atMillis
    }

    /** The last write at [path], or null when nothing ever wrote there. */
    fun lastWriteAt(path: String): LastWrite? = lastWrites[path]

    /**
     * Whether the outside world, not the app, made the last write at [path], whether or not its file
     * is still there: what a bake displaced there by another file is judged by, which the log reports
     * some suspension points after the fact.
     */
    fun lastFromOutside(path: String): Boolean = lastWrites[path]?.byApp == false

    /** When the app last moved or deleted the bytes hashing to [hash] away from [path]; null when it never did. */
    fun appTookAwayAt(
        path: String,
        hash: String,
    ): Long? = appTookAway[path to hash]
}
