package dev.npn.core.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.enums.EnumEntries

/**
 * The app's JSON configurations, one per kind of input, so how a format tolerates change is decided
 * here rather than by whichever file built its own `Json {}`.
 *
 * The journal is the exception: [dev.npn.core.journal.JournalCodec] keeps a configuration of its
 * own, because every replica must decode an event exactly alike, whatever happens to these.
 */
@Suppress("ForbiddenMethodCall") // The shared configurations the rule points every other file to.
object NpnJson {
    /**
     * For what the app writes and reads back later: device-local state, drafts, queued requests,
     * OCR sidecars. A record written by an older or newer build must still load, so unknown keys
     * are ignored and a value this build cannot use (a null, an enum constant it does not know)
     * falls back to the property's default, but for an enum that refuses one ([KnownEnumSerializer]).
     * Defaults are written out, so a record keeps the values it was made with when a later build
     * changes one.
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

/**
 * An enum stored by its constant's name, where a name this build does not know must fail the decode
 * rather than read as the property's default, as [NpnJson.Persisted] would have it: a record holding
 * another build's value then counts as unreadable, and is not written back with the default in place
 * of what that build meant.
 */
open class KnownEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val entries: EnumEntries<E>,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: E,
    ) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): E {
        val name = decoder.decodeString()
        return entries.firstOrNull { it.name == name }
            ?: throw SerializationException("${descriptor.serialName} has no constant $name in this build")
    }
}
