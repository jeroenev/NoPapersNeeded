package dev.npn.core.security

/**
 * A desktop window has no background the app can rely on (minimizing is the closest, and many window
 * managers never report it), so the lock's trigger is idle time, and [LockTimeout.IMMEDIATELY] would
 * mean locking at every pause in typing. It is not offered here, and a new lock waits five minutes.
 */
internal actual val platformLockTimeouts: List<LockTimeout> =
    listOf(LockTimeout.ONE_MINUTE, LockTimeout.FIVE_MINUTES, LockTimeout.FIFTEEN_MINUTES)

internal actual val platformDefaultLockTimeout: LockTimeout = LockTimeout.FIVE_MINUTES
