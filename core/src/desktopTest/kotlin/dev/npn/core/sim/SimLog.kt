package dev.npn.core.sim

import dev.npn.core.app.SessionLifetime
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.util.NpnLog
import dev.npn.core.util.reason
import dev.npn.core.xmp.BakeDisplacedException

/** One entry the session logged, with the virtual time and the simulation step it came in. */
data class LogEntry(
    val step: Int,
    val atMillis: Long,
    val level: NpnLog.Level,
    val message: String,
    val error: Throwable?,
) {
    override fun toString(): String =
        "[step $step, t=${atMillis}ms] ${level.name} $message" +
            error?.let { ": ${it::class.simpleName}: ${it.reason()}" }.orEmpty()
}

/**
 * The session log, kept: [bugs] are the entries that report a programming error rather than a
 * failure normal use can run into ([isBug]), which fail the simulation.
 */
class SimLog(
    private val step: () -> Int,
    private val now: () -> Long,
) : NpnLog {
    val entries = ArrayList<LogEntry>()

    /** Entries [isBug] judged bugs, not yet reported. */
    val bugs = ArrayList<LogEntry>()

    /**
     * Per replica, by the prefix its entries carry (empty for a lone replica): whether the file at a
     * path came from outside the app, the one thing that may displace a bake ([displacedBake]).
     */
    val outsideWrites = LinkedHashMap<String, (path: String) -> Boolean>()

    override fun log(
        level: NpnLog.Level,
        message: String,
        error: Throwable?,
    ) {
        val entry = LogEntry(step(), now(), level, message, error)
        entries += entry
        val displaced = error?.let(::displacedBake)
        val bug =
            if (displaced == null || level == NpnLog.Level.ERROR) {
                isBug(entry)
            } else {
                // A bake's path held a third file while the bake was undone, and the original was kept
                // under a recovery name: a race with another process or the sync tool, which wrote that
                // file. The app's own import, rename, restore or storage-path move taking the freed name
                // is one BakeLock does not serialise: a bug.
                outsideWrites.entries.none { (prefix, outside) ->
                    message.startsWith(prefix) && outside(displaced.path)
                }
            }
        if (bug) bugs += entry
    }
}

/** The [BakeDisplacedException] in [error]'s causes, if any. */
private fun displacedBake(error: Throwable): BakeDisplacedException? =
    generateSequence(error) { it.cause }.take(CHAIN_DEPTH).filterIsInstance<BakeDisplacedException>().firstOrNull()

/**
 * Whether a failure is a bug rather than one normal use can run into. Expected: anything an
 * injected fault caused (the storage failed), a session closed under the work (a crash or a close),
 * blocked journal writes (they are reported to the user), a publication the storage refused, a
 * file another process moved, replaced or deleted under the app (`NoSuchFileException`, a
 * "Target already exists" race the caller handles), a refused or superseded bake or import, and a
 * non-PDF. A bake displaced by another file ([BakeDisplacedException]) is judged by the log
 * ([SimLog.outsideWrites]): expected only when that file came from outside the app. A bug: every other
 * [IllegalStateException] (a failed `check`), an [IllegalArgumentException] (a failed `require`),
 * the JVM's programming errors (null, bounds, casts, concurrent modification), and SQLite's
 * constraint failures.
 *
 * Logged at [NpnLog.Level.ERROR] is always a bug report: core logs at that level only what it could
 * not put right.
 */
fun isBug(entry: LogEntry): Boolean {
    if (entry.level == NpnLog.Level.ERROR) return true
    val error = entry.error ?: return false
    return isBugFailure(error)
}

/** [isBug] for a failure an operation threw or a coroutine ended with. */
fun isBugFailure(error: Throwable): Boolean {
    if (error.isInjected()) return false
    val chain = generateSequence(error) { it.cause }.take(CHAIN_DEPTH).toList()
    if (chain.any { it.isExpected() }) return false
    return chain.any { it.isProgrammingError() }
}

private fun Throwable.isExpected(): Boolean =
    this is MetadataWriteBlocked ||
        // An import of bytes a relink superseded is refused (ImportFailure.SUPERSEDED), a split part's included.
        this is dev.npn.core.index.SupersededImportException ||
        // A bake the PDF cannot carry, or whose rewrite did not verify: BakeFailure.UNSUPPORTED_PDF.
        this is dev.npn.core.xmp.BakeRefusedException ||
        this is VerifiedPublicationUnavailableException ||
        this is VerifiedPublicationFailedException ||
        this is java.nio.file.NoSuchFileException ||
        this is java.nio.file.FileAlreadyExistsException ||
        this is dev.npn.core.pdf.PdfOpenException ||
        message?.let { msg -> EXPECTED_MESSAGES.any { msg.contains(it) } } == true

private fun Throwable.isProgrammingError(): Boolean =
    this is IllegalStateException ||
        this is IllegalArgumentException ||
        this is NullPointerException ||
        this is IndexOutOfBoundsException ||
        this is ClassCastException ||
        this is ConcurrentModificationException ||
        this is NoSuchElementException ||
        this is ArithmeticException ||
        this is AssertionError ||
        this::class.qualifiedName.orEmpty().contains("SQLite") ||
        this::class.qualifiedName.orEmpty().contains("Serialization")

/**
 * Messages of failures that are expected outcomes of a race with another process or a close, in
 * the words core raises them with.
 */
private val EXPECTED_MESSAGES =
    listOf(
        SessionLifetime.CLOSED,
        // A move or write onto a path another process (or a concurrent step) just took.
        "Target already exists",
        // A file another process put where the app expected a directory, or the other way round.
        "Is a directory",
        "Not a directory",
        // An import that found its bytes back at the document's own path, which another process
        // deleted before the import recorded them: the import fails and is taken back.
        "Imported file vanished",
    )

private const val CHAIN_DEPTH = 16
