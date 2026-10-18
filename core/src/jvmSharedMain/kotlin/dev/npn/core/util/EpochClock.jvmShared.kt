package dev.npn.core.util

@Suppress("ForbiddenMethodCall") // The one wall-clock read, behind SystemEpochClock.
actual fun systemNowMillis(): Long = System.currentTimeMillis()
