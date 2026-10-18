package dev.npn.core.settings

/**
 * The persisted library location string is one of two shapes, and the platform wiring picks the
 * filesystem implementation by looking at it: a plain absolute path opens the direct-filesystem
 * library (desktop, and Android's default mode), a `content://` tree uri opens Android's Storage
 * Access Framework library. Keeping the mode in the string rather than in a separate setting
 * means an installation that predates the direct mode keeps opening its SAF library unchanged.
 */
object LibraryLocations {
    private const val CONTENT_SCHEME = "content://"

    fun isContentUri(location: String): Boolean = location.startsWith(CONTENT_SCHEME)

    /**
     * A short, stable, filesystem-safe key derived from a library location string. Used to give
     * each opened library its own device-local databases and thumbnails. Not cryptographic; only
     * needs to be stable across runs and collision-resistant enough for a handful of libraries.
     *
     * Never change it: the key names where the library's `device.db` lives (its folder on desktop,
     * its file name on Android), what only this device knows and nothing could rebuild, so a new key
     * for the same location orphans that data.
     * `LibraryLocationsTest` pins it.
     */
    fun key(location: String): String {
        // FNV-1a 64-bit over the UTF-8 bytes; rendered as lowercase hex. Deterministic and
        // dependency-free (available in commonMain).
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037 (offset basis)
        val prime = 0x100000001b3L
        for (b in location.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}
