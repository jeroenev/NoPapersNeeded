package dev.npn.core.rules

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.FtsDao
import dev.npn.core.db.toRule
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.DocProperty
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
    suspend fun applyToDoc(docId: String, content: String? = null, organize: Boolean = true) = mutex.withLock {
        apply(documentDao.getByDocId(docId) ?: return@withLock, content, organize)
    }

    /**
     * Apply the current rule set to every live document. This is awaited and serialized with OCR
     * callbacks so saving/enabling several rules cannot race or duplicate writes.
     */
    suspend fun applyToExistingDocs() = mutex.withLock {
        val docs = documentDao.getAllLive()
        for (doc in docs) {
            apply(doc, ftsDao.contentForDoc(doc.docId), organize = true)
        }
    }

    private suspend fun enabledRules(): List<AutoMatchRule> =
        ruleDao.getAll()
            .map { it.toRule() }
            .filter { it.enabled && it.pattern.isNotBlank() }
            .sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id))

    private suspend fun apply(doc: dev.npn.core.db.DocumentEntity, content: String?, organize: Boolean) {
        val rules = enabledRules()
        val ops = buildOps(doc.docId, rules, content, doc.title, doc.fileName)
        if (ops.isEmpty()) return
        val changed = coordinator.commitBuilt { state ->
            ops.filter { op ->
                when (val journalOp = op.op) {
                    is JournalOp.TagAdd -> journalOp.tag !in state.effectiveTags(doc.docId)
                    is JournalOp.PropSet -> state.effectiveProp(doc.docId, journalOp.key) != journalOp.value
                    else -> true
                }
            }
        }
        if (changed && organize) onMetadataApplied(doc.docId)
    }

    private fun buildOps(
        docId: String,
        rules: List<AutoMatchRule>,
        content: String?,
        title: String?,
        fileName: String,
    ): List<PendingOp> {
        val ops = mutableListOf<PendingOp>()
        val matchedTags = LinkedHashSet<String>()

        for (rule in rules) {
            val value = when (rule.field) {
                RuleField.CONTENT -> content
                RuleField.TITLE -> title
                RuleField.FILE_NAME -> fileName
            }
            if (!RuleMatcher.matches(rule, value)) continue
            rule.tags.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { matchedTags += it }
            rule.correspondent?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ops.removeAll { op ->
                    val value = op.op
                    value is JournalOp.PropSet && value.key == DocProperty.CORRESPONDENT
                }
                ops += PendingOp.propSet(docId, DocProperty.CORRESPONDENT, it)
            }
            rule.docType?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ops.removeAll { op ->
                    val value = op.op
                    value is JournalOp.PropSet && value.key == DocProperty.DOC_TYPE
                }
                ops += PendingOp.propSet(docId, DocProperty.DOC_TYPE, it)
            }
        }

        matchedTags.forEach { ops += PendingOp.tagAdd(docId, it) }
        return ops
    }
}
