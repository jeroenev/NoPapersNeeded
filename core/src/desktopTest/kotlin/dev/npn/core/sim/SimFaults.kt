package dev.npn.core.sim

import java.io.IOException

/** Every member of [dev.npn.core.fs.LibraryFileSystem] that touches storage, as a fault can name it. */
enum class FsMethod {
    LIST_INDEXABLE,
    LIST_UNDER,
    EXISTS,
    STAT,
    READ,
    READ_TAIL,
    WRITE_ONCE,
    PUBLISH,
    MOVE,
    COPY_ONCE,
    SAME_FILE,
    DELETE,
    SHA256,
    IMPORT_EXTERNAL,
    CLAIM,
    ;

    /** Whether the call changes what is stored, so a fault [SimFaults.Point.AFTER] it lands first. */
    val writes: Boolean get() = this in setOf(WRITE_ONCE, PUBLISH, MOVE, COPY_ONCE, DELETE, IMPORT_EXTERNAL, CLAIM)
}

/** What [SimFaults] throws: an I/O failure the storage reported, which the app must survive. */
class SimFaultException(
    message: String,
) : IOException(message)

/**
 * Failures injected at the storage boundary of an [InMemoryLibraryFileSystem]. A [Rule] fails the
 * next [Rule.times] calls of its [Rule.method] (any, when null) at a path under its
 * [Rule.pathPrefix] (any, when null), either [Point.BEFORE] the call has an effect or [Point.AFTER]
 * it landed, as a rename that succeeded but reported failure does.
 *
 * It subsumes the app tests' `FileSystemFaults` (publish failures, a failed move or delete, refused
 * deletes under a prefix) for every member, not just three.
 */
class SimFaults {
    enum class Point { BEFORE, AFTER }

    data class Rule(
        val method: FsMethod?,
        val pathPrefix: String?,
        val point: Point,
        val times: Int,
    ) {
        override fun toString(): String = "${method ?: "any"}@${pathPrefix ?: "*"} ${point.name.lowercase()} x$times"
    }

    private class Armed(
        val rule: Rule,
        var left: Int,
    )

    private val armed = ArrayList<Armed>()

    /** How many faults have fired, for the trace. */
    var fired = 0
        private set

    fun arm(rule: Rule) {
        armed += Armed(rule, rule.times)
    }

    fun disarmAll() = armed.clear()

    val pending: List<Rule> get() = armed.map { it.rule }

    /** Throw if an armed rule matches [method] at [path] at [point]. */
    fun check(
        method: FsMethod,
        path: String,
        point: Point,
    ) {
        val hit =
            armed.firstOrNull {
                it.rule.point == point &&
                    (it.rule.method == null || it.rule.method == method) &&
                    (it.rule.pathPrefix == null || path.startsWith(it.rule.pathPrefix))
            } ?: return
        if (--hit.left <= 0) armed.remove(hit)
        fired++
        throw SimFaultException("Injected fault: $method ${point.name.lowercase()} at '$path'")
    }
}

/** Whether [this] or anything in its cause chain is an injected [SimFaultException]. */
fun Throwable.isInjected(): Boolean = generateSequence(this) { it.cause }.take(DEPTH).any { it is SimFaultException }

private const val DEPTH = 16
