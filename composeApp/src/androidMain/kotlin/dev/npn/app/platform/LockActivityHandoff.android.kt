package dev.npn.app.platform

import dev.npn.core.security.AppLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One pending handoff from the foreground activity to a system or external activity (a picker, the
 * share sheet, the print dialog): the app expects the user back, so [LockLifecycle] gives the time
 * away a grace ([LockLifecycle.HANDOFF_GRACE_MILLIS]) instead of the bare timeout.
 */
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

/**
 * The app lock's Android half: the activity's stops and starts, and the screen going off, turned
 * into [AppLock]'s background and foreground. Every real stop records the time the app was left;
 * a stop for a handoff only allows that time a bounded grace, so leaving the handed-off app for Home
 * still locks once enough time has passed. A configuration change is not leaving.
 */
class LockLifecycle(
    private val lock: AppLock,
) {
    /**
     * The activity stopped. [handoff] says it stopped for an activity it started and expects back;
     * a finishing activity (Back) or a screen that is already off is leaving all the same.
     */
    fun onStop(
        changingConfigurations: Boolean,
        finishing: Boolean,
        screenOn: Boolean,
        handoff: Boolean,
    ) {
        if (changingConfigurations) return
        val grace = if (handoff && !finishing && screenOn) HANDOFF_GRACE_MILLIS else 0L
        lock.onBackground(grace)
    }

    /** The activity is visible again; the lock engages if the time away says so. */
    fun onStart() {
        lock.onForeground()
    }

    /** The screen went off, wherever the user was: a handoff's grace ends. */
    fun onScreenOff() {
        lock.onScreenOff()
    }

    companion object {
        /**
         * How long a handoff may keep the app unlocked when that is longer than the timeout: enough
         * to pick a file or a share target, short enough that walking away from the picker locks.
         */
        const val HANDOFF_GRACE_MILLIS = 3L * 60_000L
    }
}
