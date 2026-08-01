package dev.npn.core.app

/**
 * App-local storage locations for one opened library. All three are absolute paths to plain
 * directories outside the synced library folder so the device-private cache is never synced.
 *
 * - [dbDir]     holds the Room index database file for this library.
 * - [thumbsDir] holds cached first-page thumbnail PNGs.
 * - [tmpDir]    scratch space (baked-copy exports, transient files).
 *
 * Per-library isolation is achieved by the caller keying these directories on a stable hash of the
 * library location string (see [libraryKey]).
 */
data class AppDirs(
    val dbDir: String,
    val thumbsDir: String,
    val tmpDir: String,
) {
    companion object {
        /**
         * A short, stable, filesystem-safe key derived from a library location string. Used to
         * give each opened library its own db/thumbs subdirectory. Not cryptographic; only needs
         * to be stable across runs and collision-resistant enough for a handful of libraries.
         */
        fun libraryKey(location: String): String {
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
}
