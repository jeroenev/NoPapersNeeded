package dev.npn.core.rules

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.FtsDao
import dev.npn.core.db.toRule
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.effectiveFieldType
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies enabled auto-matching rules by turning matches into [PendingOp]s and committing them
 * through [JournalCoordinator]. Runs as a side effect when OCR text becomes available and when
 * a rule is saved/updated.
 */
class RuleApplier(
    private val ruleDao: AutoMatchRuleDao,
    private val ftsDao: FtsDao,
    private val documentDao: DocumentDao,
    private val coordinator: JournalCoordinator,
    private val onMetadataApplied: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Apply all rules whose selected field is available for one document. */
    suspend fun applyToDoc(
        docId: String,
        content: String? = null,
        organize: Boolean = true,
    ) = mutex.withLock {
        apply(documentDao.getByDocId(docId) ?: return@withLock, content, organize)
    }

    /**
     * Apply the current rule set to every live document. This is awaited and serialized with OCR
     * callbacks so saving/enabling several rules cannot race or duplicate writes.
     */
    suspend fun applyToExistingDocs() =
        mutex.withLock {
            val docs = documentDao.getAllLive()
            for (doc in docs) {
                apply(doc, ftsDao.contentForDoc(doc.docId), organize = true)
            }
        }

    private suspend fun enabledRules(): List<AutoMatchRule> =
        ruleDao
            .getAll()
            .map { it.toRule() }
            .filter { it.enabled && it.pattern.isNotBlank() }
            .sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id))

    private suspend fun apply(
        doc: dev.npn.core.db.DocumentEntity,
        content: String?,
        organize: Boolean,
    ) {
        val rules = enabledRules()
        if (rules.isEmpty()) return
        val changed =
            coordinator.commitBuilt { state ->
                // Matching reads the folded state, not the projection, so a rule on a field sees
                // the value the journal holds even before the index has caught up.
                val fields = state.effectiveFields(doc.docId)
                val tags = state.effectiveTags(doc.docId)
                val matched = matchRules(rules, content, fields, doc.fileName)
                buildList {
                    // Write when the effective value DIFFERS (not only when empty): idempotent, so a
                    // rule fires once and then goes quiet, yet still corrects a non-matching value.
                    for ((key, raw) in matched.sets) {
                        val value = canonicalOrNull(state, key, raw) ?: continue
                        if (fields[key] != value) add(PendingOp.fieldSet(doc.docId, key, value))
                    }
                    for (tag in matched.tags) if (tag !in tags) add(PendingOp.tagAdd(doc.docId, tag))
                }
            }
        if (changed && organize) onMetadataApplied(doc.docId)
    }

    /**
     * A rule value goes through the same canonical form the metadata writer enforces; a value
     * the field's type refuses (e.g. "soon" for a DATE) is skipped rather than written off-type.
     */
    private fun canonicalOrNull(
        state: FoldedState,
        key: String,
        raw: String,
    ): String? {
        val type = state.effectiveFieldType(key)
        return (FieldValues.canonicalize(type, raw) as? FieldParse.Ok)?.canonical
    }

    private class Matched(
        val tags: Set<String>,
        val sets: Map<String, String>,
    )

    /** Every enabled rule's actions, in priority order; a later rule's value for a key wins. */
    private fun matchRules(
        rules: List<AutoMatchRule>,
        content: String?,
        fields: Map<String, String>,
        fileName: String,
    ): Matched {
        val tags = LinkedHashSet<String>()
        val sets = LinkedHashMap<String, String>()
        for (rule in rules) {
            val value =
                when (val field = rule.field) {
                    RuleField.Content -> content
                    RuleField.FileName -> fileName
                    is RuleField.Field -> fields[field.key]
                }
            if (!RuleMatcher.matches(rule, value)) continue
            rule.tags
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { tags += it }
            for ((key, raw) in rule.sets) {
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) sets[key] = trimmed
            }
        }
        return Matched(tags, sets)
    }
}
