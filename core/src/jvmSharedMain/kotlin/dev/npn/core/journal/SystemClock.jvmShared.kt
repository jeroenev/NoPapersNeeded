package dev.npn.core.journal

@Suppress("ForbiddenMethodCall") // The one wall-clock read, behind SystemEpochClock.
actual fun systemNowMillis(): Long = System.currentTimeMillis()
