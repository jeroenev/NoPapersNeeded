package dev.npn.core.util

import kotlinx.serialization.json.Json

/**
 * The app's JSON configurations, one per kind of input, so how a format tolerates change is decided
 * here rather than by whichever file built its own `Json {}`.
 *
 * The journal is the exception: [dev.npn.core.journal.JournalCodec] keeps a configuration of its
 * own, because every replica must decode an event exactly alike, whatever happens to these.
 */
object NpnJson {
    /**
     * For what the app writes and reads back later: device-local state, drafts, queued requests,
     * OCR sidecars. A record written by an older or newer build must still load, so unknown keys
     * are ignored and a value this build cannot use (a null, an enum constant it does not know)
     * falls back to the property's default. Defaults are written out, so a record keeps the values
     * it was made with when a later build changes one.
     */
    val Persisted: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

    /** For input the app did not write: model output, third-party API responses. */
    val Lenient: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
}
