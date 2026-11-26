package dev.npn.core.security

/**
 * A phone has a real background: leaving the app is the moment to lock, so every interval is
 * offered and a new lock locks as soon as the app is left.
 */
internal actual val platformLockTimeouts: List<LockTimeout> = LockTimeout.entries

internal actual val platformDefaultLockTimeout: LockTimeout = LockTimeout.IMMEDIATELY
