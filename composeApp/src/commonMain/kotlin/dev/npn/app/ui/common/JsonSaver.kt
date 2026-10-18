package dev.npn.app.ui.common

import androidx.compose.runtime.saveable.Saver
import dev.npn.core.util.NpnJson
import kotlinx.serialization.KSerializer

/**
 * Saves a value across recreation as its [NpnJson.Persisted] JSON. A null value saves nothing, so
 * it restores as the initial value; pass `serializer().nullable` for state that may be null (an
 * open dialog, or none).
 */
fun <T> jsonSaver(serializer: KSerializer<T>): Saver<T, String> =
    Saver(
        save = { value -> value?.let { NpnJson.Persisted.encodeToString(serializer, it) } },
        restore = { NpnJson.Persisted.decodeFromString(serializer, it) },
    )
