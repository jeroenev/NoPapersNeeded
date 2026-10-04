package dev.npn.core.ai

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType

/**
 * One field as an edit run asks about it: the registry definition (label, type, hint), the run's
 * [mode], and the existing [vocabulary] a TEXT field's answer should reuse verbatim. Built once per
 * request in the facade from [AiFieldConfig] + the registry, then shared by the prompt (what to
 * ask) and the parser (what to accept), so the two can never disagree about a field's type.
 */
data class AiFieldSpec(
    val def: FieldDef,
    val mode: AiFieldMode,
    val vocabulary: List<String> = emptyList(),
) {
    val key: String get() = def.key

    companion object {
        /**
         * A field with more distinct values than this is asked for without a vocabulary: the list
         * would dwarf the document. A field that shares none ([FieldDef.sharesVocabulary], so not a
         * per-document one like the title) is never given one.
         */
        const val VOCABULARY_THRESHOLD = 100

        /** The stand-in definition for a requested key the registry does not list: plain text. */
        fun unregistered(key: String): FieldDef =
            FieldDef(
                key = key,
                label = key,
                type = FieldType.TEXT,
                showByDefault = false,
                aiEnabled = false,
                position = 0,
            )
    }
}
