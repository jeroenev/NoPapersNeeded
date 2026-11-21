package dev.npn.core.rules

import dev.npn.core.model.FieldKeys
import dev.npn.core.util.KnownEnumSerializer
import dev.npn.core.util.NpnJson
import dev.npn.core.util.carriesUnknownData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * User-defined rule that assigns tags and/or field values by matching document text against a
 * pattern.
 *
 * Rules belong to the library: the list is one synced journal setting ([AutoMatchRuleJson]),
 * cached in Room, so every device matches with the same rules and the same field keys. Their
 * actions are applied as ordinary journal ops via [dev.npn.core.index.JournalCoordinator].
 */
@Serializable
data class AutoMatchRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val field: RuleField = RuleField.Content,
    val algorithm: MatchAlgorithm = MatchAlgorithm.ANY_WORD,
    val pattern: String,
    val tags: List<String> = emptyList(),
    /** Field key -> value to set when the rule matches. Later rules override earlier ones. */
    val sets: Map<String, String> = emptyMap(),
    val position: Int = 0,
) {
    /**
     * This rule as it is stored and matched: its name and pattern trimmed, and its tags and set
     * values trimmed with the blank ones dropped. A rule is normalized where it comes in, saved
     * ([dev.npn.core.app.AutoMatchRuleRepository.save]) or decoded ([AutoMatchRuleJson]), since a
     * list another device's build wrote may hold what this one would not.
     */
    fun normalized(): AutoMatchRule =
        copy(
            name = name.trim(),
            pattern = pattern.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
            sets = sets.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
        )

    companion object {
        /** The order rules are stored, listed and run in: by position, then by id. */
        internal val ORDER: Comparator<AutoMatchRule> = compareBy(AutoMatchRule::position, AutoMatchRule::id)
    }
}

/** Which part of the document a rule matches against. */
@Serializable(with = RuleFieldSerializer::class)
sealed class RuleField {
    /** The indexed text (OCR or embedded). */
    data object Content : RuleField()

    /** The file name, extension included. */
    data object FileName : RuleField()

    /** One registry field's value by key. */
    data class Field(
        val key: String,
    ) : RuleField()

    /** Compact string form: `content`, `fileName`, `field:<key>`. */
    val wire: String
        get() =
            when (this) {
                Content -> "content"
                FileName -> "fileName"
                is Field -> "field:$key"
            }

    companion object {
        /** Inverse of [wire]; also reads the pre-registry enum names. Null for anything else. */
        fun fromWire(value: String?): RuleField? =
            when (value) {
                null -> null
                "content", "CONTENT" -> Content
                "fileName", "FILE_NAME" -> FileName
                "TITLE" -> Field(FieldKeys.TITLE)
                else -> value.removePrefix("field:").takeIf { it != value && it.isNotBlank() }?.let(::Field)
            }
    }
}

/**
 * [RuleField] travels as its [RuleField.wire] string, so the stored rule list stays hand-readable. A
 * kind of field this build does not know fails the decode: matched against the content instead, the
 * rule would tag what it was never meant to, and an edit would write it back that way.
 */
object RuleFieldSerializer : KSerializer<RuleField> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RuleField", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RuleField,
    ) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): RuleField {
        val wire = decoder.decodeString()
        return RuleField.fromWire(wire) ?: throw SerializationException("No rule field $wire in this build")
    }
}

/** Matching algorithm for [AutoMatchRule.pattern]. */
@Serializable(with = MatchAlgorithmSerializer::class)
enum class MatchAlgorithm {
    EXACT,
    ANY_WORD,
    ALL_WORDS,
    REGEX,
    FUZZY,
}

/** [MatchAlgorithm] by name; one this build does not know fails the decode, as a field kind does. */
object MatchAlgorithmSerializer : KnownEnumSerializer<MatchAlgorithm>("MatchAlgorithm", MatchAlgorithm.entries)

/**
 * The codec for the stored rule list: the journal setting, and the device setting it was adopted
 * from. Reads the pre-registry shape too, where a rule carried `correspondent` and `docType` as
 * top-level strings instead of [AutoMatchRule.sets]. A list with a rule this build cannot match as
 * its writer meant (a field kind or an algorithm it does not know, or a key it would drop, see
 * [carriesUnknownData]) fails to decode as a whole.
 */
internal object AutoMatchRuleJson {
    private val json = NpnJson.Persisted
    private val listSerializer = ListSerializer(LegacyAware)

    /** The rules in [text], each [AutoMatchRule.normalized]. */
    fun decodeList(text: String): List<AutoMatchRule> {
        val written = json.parseToJsonElement(text)
        val rules = json.decodeFromJsonElement(listSerializer, written)
        if (carriesUnknownData(written, json.encodeToJsonElement(listSerializer, rules), LegacyAware.retired)) {
            throw SerializationException("The rule list holds what this build does not know")
        }
        return rules.map(AutoMatchRule::normalized)
    }

    fun encodeList(rules: List<AutoMatchRule>): String = json.encodeToString(listSerializer, rules)

    private object LegacyAware : JsonTransformingSerializer<AutoMatchRule>(AutoMatchRule.serializer()) {
        private val legacyKeys = mapOf("correspondent" to FieldKeys.CORRESPONDENT, "docType" to FieldKeys.DOC_TYPE)

        /** The pre-registry keys, read into [AutoMatchRule.sets]; a rule written now has none. */
        val retired: Set<String> = legacyKeys.keys

        override fun transformDeserialize(element: JsonElement): JsonElement {
            val obj = element.jsonObject
            if (legacyKeys.keys.none { it in obj }) return element
            val sets = LinkedHashMap<String, JsonElement>()
            for ((legacy, key) in legacyKeys) {
                val value = obj[legacy]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (!value.isNullOrBlank()) sets[key] = JsonPrimitive(value.trim())
            }
            obj["sets"]?.jsonObject?.let { sets.putAll(it) }
            return buildJsonObject {
                for ((k, v) in obj) if (k !in legacyKeys && k != "sets") put(k, v)
                put("sets", JsonObject(sets))
            }
        }
    }
}
