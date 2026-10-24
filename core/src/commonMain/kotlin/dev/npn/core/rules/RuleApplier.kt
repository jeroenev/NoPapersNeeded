package dev.npn.core.rules

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldValues
import dev.npn.core.model.canonicalOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies enabled auto-matching rules by turning matches into [PendingOp]s and committing them
 * through [JournalCoordinator]. Runs on text new to the library (this device's OCR produced it,
 * `OcrPipeline`'s `onTextProduced`), after a write to a document's fields or file name, and over
 * every document when a rule is saved or enabled. Never on text a rescan, a rebuilt index or a
 * synced sidecar merely re-indexes: what the rules write is journal data, so it must not depend on
 * when or where an index was built.
 *
 * Rules only fill in: a rule sets a field the document has never had a value for (a cleared one
 * counts as had) and adds a tag it has never carried (a removed one counts as carried), by the fold
 * the write lands on, read over the document and every document a relink made it of. What a person,
 * another device's rules or AI set or took away stays, and applying the rules again changes nothing,
 * wherever and however often it runs.
 *
 * [rules] reads the rule list wherever it is stored, each rule [AutoMatchRule.normalized]. Each call
 * here reads it once and compiles the enabled rules once, however many documents it then matches.
 */
internal class RuleApplier(
    private val rules: suspend () -> List<AutoMatchRule>,
    private val ftsDao: FtsDao,
    private val documentDao: DocumentDao,
    private val coordinator: JournalCoordinator,
    private val onMetadataApplied: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /**
     * Apply every rule to one document. [content] is its text when the caller has it at hand (OCR
     * just produced it); without it, a rule on the content reads the indexed text, so a write that
     * is no OCR (a field edit, a rename) matches the same rules a pass over the library does.
     */
    suspend fun applyToDoc(
        docId: String,
        content: String? = null,
        organize: Boolean = true,
    ) = mutex.withLock {
        val compiled = enabledRules()
        if (compiled.isEmpty()) return@withLock
        val doc = documentDao.getByDocId(docId) ?: return@withLock
        apply(doc, content ?: indexedText(docId, compiled), compiled, organize)
    }

    /**
     * Apply the current rule set to every live document: what saving or enabling a rule does, and
     * the only way a rule reaches a document whose text was there before it. This is awaited and
     * serialized with OCR callbacks so saving/enabling several rules cannot race or duplicate writes.
     */
    suspend fun applyToExistingDocs() =
        mutex.withLock {
            val compiled = enabledRules()
            if (compiled.isEmpty()) return@withLock
            for (doc in documentDao.getAllLive()) {
                apply(doc, indexedText(doc.docId, compiled), compiled, organize = true)
            }
        }

    /** [docId]'s indexed text when one of [compiled] matches on the content, else null unread. */
    private suspend fun indexedText(
        docId: String,
        compiled: List<CompiledRule>,
    ): String? = if (compiled.any { it.rule.field == RuleField.Content }) ftsDao.textOf(docId) else null

    /** The enabled rules in priority order, compiled for the pass that reads them. */
    private suspend fun enabledRules(): List<CompiledRule> =
        rules()
            .filter { it.enabled && it.pattern.isNotBlank() }
            .sortedWith(AutoMatchRule.ORDER)
            .map(RuleMatcher::compile)

    private suspend fun apply(
        doc: DocumentEntity,
        content: String?,
        compiled: List<CompiledRule>,
        organize: Boolean,
    ) {
        val changed =
            coordinator.commitBuilt { state ->
                // Matching reads the folded state, not the projection, so a rule on a field sees
                // the value the journal holds even before the index has caught up.
                val fields = state.effectiveFields(doc.docId)
                val matched = matchRules(compiled, content, fields, doc.fileName)
                // Fill in only: a register the journal holds, a clear included, is someone's value.
                // Over the whole relink chain: a relink copies values and live tags, never a clear
                // or a removal, so what was taken away before it is read on its predecessors.
                val chain = state.chainOf(doc.docId)
                val held = chain.flatMapTo(HashSet()) { state.fieldRegisters(it).keys }
                val carried = chain.flatMapTo(HashSet()) { id -> state.tagsEverAdded(id).map { it.lowercase() } }
                buildList {
                    for ((key, raw) in matched.sets - held) {
                        val value = canonicalOrNull(state, key, raw) ?: continue
                        add(PendingOp.fieldSet(doc.docId, key, value))
                    }
                    for (tag in matched.tags) if (tag.lowercase() !in carried) add(PendingOp.tagAdd(doc.docId, tag))
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
        return FieldValues.canonicalize(type, raw).canonicalOrNull
    }

    private class Matched(
        val tags: Set<String>,
        val sets: Map<String, String>,
    )

    /** Every enabled rule's actions, in priority order; a later rule's value for a key wins. */
    private fun matchRules(
        compiled: List<CompiledRule>,
        content: String?,
        fields: Map<String, String>,
        fileName: String,
    ): Matched {
        val tags = LinkedHashSet<String>()
        val sets = LinkedHashMap<String, String>()
        for (candidate in compiled) {
            val rule = candidate.rule
            val value =
                when (val field = rule.field) {
                    RuleField.Content -> content
                    RuleField.FileName -> fileName
                    is RuleField.Field -> fields[field.key]
                }
            if (!candidate.matches(value)) continue
            tags += rule.tags
            sets += rule.sets
        }
        return Matched(tags, sets)
    }
}
