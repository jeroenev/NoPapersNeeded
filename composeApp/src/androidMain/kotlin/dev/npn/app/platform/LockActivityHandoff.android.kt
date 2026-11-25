package dev.npn.app.platform

import java.util.concurrent.atomic.AtomicBoolean

/** One pending handoff from the foreground activity to a system or external activity. */
object LockActivityHandoff {
    private val pending = AtomicBoolean(false)

    fun started() {
        pending.set(true)
    }

    fun clear() {
        pending.set(false)
    }

    fun consume(): Boolean = pending.getAndSet(false)
}

/** A screen off or finishing activity takes precedence over a handoff. */
fun lockOnStop(
    changingConfigurations: Boolean,
    finishing: Boolean,
    screenOn: Boolean,
    handoff: Boolean,
): Boolean = !changingConfigurations && (finishing || !screenOn || !handoff)
