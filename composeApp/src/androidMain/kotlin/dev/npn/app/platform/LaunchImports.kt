package dev.npn.app.platform

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The imports of the share or open intents activities were launched with, in this process: one
 * instance per process. The system hands an activity its launch intent again when it recreates it
 * and when its task is reopened from Recents, so only the creation that received it imports it. The
 * exception is an import a process death cut short: the activity saves it while it runs
 * ([unfinished]), and a creation in a later process runs it again. An import is idempotent (the same
 * bytes again are a duplicate), so that retry is safe.
 */
class LaunchImports {
    /** A launch import that had not finished when its activity saved its state. */
    data class Unfinished(
        val token: String,
        val process: String,
    )

    // A random id, not the pid: a later process can be given the same pid.
    private val process = UUID.randomUUID().toString()
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Records a launch import as running; [finish] it with the token this returns. */
    fun start(): String = UUID.randomUUID().toString().also(running::add)

    fun finish(token: String) {
        running.remove(token)
    }

    /** What an activity saves for the launch import [token] names: null once it has finished. */
    fun unfinished(token: String?): Unfinished? = token?.takeIf { it in running }?.let { Unfinished(it, process) }

    /**
     * Whether an activity being created imports its launch intent. A fresh launch does; a
     * recreation ([restored]) or a return from Recents ([fromHistory]) does not, unless the state it
     * restored names an [unfinished] import that ran in another process.
     */
    fun importsLaunchIntent(
        restored: Boolean,
        fromHistory: Boolean,
        unfinished: Unfinished?,
    ): Boolean = if (unfinished != null) unfinished.process != process else !restored && !fromHistory
}
