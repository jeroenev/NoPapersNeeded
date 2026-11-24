package dev.npn.core.util

/**
 * Android's elapsed-realtime clock: monotonic, and it keeps counting while the device sleeps, which
 * is what a background timeout must measure. It is reset by a reboot, which is no loss: a cold
 * start locks the app anyway.
 */
actual fun monotonicNowMillis(): Long = android.os.SystemClock.elapsedRealtime()
