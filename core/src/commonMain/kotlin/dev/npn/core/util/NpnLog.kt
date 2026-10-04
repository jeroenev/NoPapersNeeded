package dev.npn.core.util

/**
 * Where diagnostics go: logcat on Android, stderr plus a size-capped file on desktop. Background
 * work has no caller to report a failure to, so it logs one here instead of dropping it; that log
 * is what makes a user's report diagnosable.
 *
 * Carried on [dev.npn.core.app.SessionDeps.log]. An implementation must not throw: a log call sits
 * in the failure path of whatever it reports.
 */
fun interface NpnLog {
    fun log(
        level: Level,
        message: String,
        error: Throwable?,
    )

    enum class Level { INFO, WARN, ERROR }

    companion object {
        /** Drops every entry, for tests and hosts with nowhere to put one. */
        val None: NpnLog = NpnLog { _, _, _ -> }
    }
}

fun NpnLog.info(message: String) = log(NpnLog.Level.INFO, message, null)

fun NpnLog.warn(
    message: String,
    error: Throwable? = null,
) = log(NpnLog.Level.WARN, message, error)

fun NpnLog.error(
    message: String,
    error: Throwable? = null,
) = log(NpnLog.Level.ERROR, message, error)

/**
 * Run [block] as background work nobody awaits: a failure is logged as "[what] failed" and null is
 * returned in place of the result. Cancellation is rethrown, never logged; it is not a failure.
 */
inline fun <T> NpnLog.runLogged(
    what: String,
    block: () -> T,
): T? = runCatchingCancellable(block).onFailure { warn("$what failed", it) }.getOrNull()
