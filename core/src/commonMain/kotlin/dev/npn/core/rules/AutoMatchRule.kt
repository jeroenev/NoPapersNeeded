package dev.npn.core.rules

import dev.npn.core.model.FieldKeys
import dev.npn.core.util.NpnJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
 * actions are applied as ordinary journal ops via [dev.npn.core.app.JournalCoordinator].
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
)

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

/** [RuleField] travels as its [RuleField.wire] string, so the stored rule list stays hand-readable. */
object RuleFieldSerializer : KSerializer<RuleField> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RuleField", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RuleField,
    ) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): RuleField =
        RuleField.fromWire(decoder.decodeString()) ?: RuleField.Content
}

/** Matching algorithm for [AutoMatchRule.pattern]. */
@Serializable
enum class MatchAlgorithm {
    EXACT,
    ANY_WORD,
    ALL_WORDS,
    REGEX,
    FUZZY,
}

/**
 * The codec for the stored rule list: the journal setting, and the device setting it was adopted
 * from. Reads the pre-registry shape too, where a rule carried `correspondent` and `docType` as
 * top-level strings instead of [AutoMatchRule.sets].
 */
object AutoMatchRuleJson {
    private val json = NpnJson.Persisted
    private val listSerializer = ListSerializer(LegacyAware)

    fun decodeList(text: String): List<AutoMatchRule> = json.decodeFromString(listSerializer, text)

    fun encodeList(rules: List<AutoMatchRule>): String = json.encodeToString(listSerializer, rules)

    fun decode(text: String): AutoMatchRule = json.decodeFromString(LegacyAware, text)

    fun encode(rule: AutoMatchRule): String = json.encodeToString(LegacyAware, rule)

    private object LegacyAware : JsonTransformingSerializer<AutoMatchRule>(AutoMatchRule.serializer()) {
        private val legacyKeys = mapOf("correspondent" to FieldKeys.CORRESPONDENT, "docType" to FieldKeys.DOC_TYPE)

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
