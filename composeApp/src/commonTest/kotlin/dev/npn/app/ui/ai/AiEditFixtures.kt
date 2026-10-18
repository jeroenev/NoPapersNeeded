package dev.npn.app.ui.ai

import androidx.lifecycle.SavedStateHandle
import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.app.DocListItem
import dev.npn.core.app.aiedit.AiEditRun
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.BatchResult
import dev.npn.core.app.aiedit.EditProgress
import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.app.aiedit.ReviewField
import dev.npn.core.app.aiedit.ScopeKey
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.listItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// Fixtures for the AiEditViewModel suites. The viewmodel drives an AiEditRun, which has its own
// suites (AiEditRun*Test in core), so these run it against a scripted one and check only what the viewmodel
// asks of it and how it shows the answers.

/**
 * The registry keys the seed marks aiEnabled, in registry order — what the viewmodel seeds its
 * fieldOptions with (title, correspondent, docType, docDate; notes is aiEnabled=false).
 */
internal val seededAiFieldKeys: List<String> =
    FieldKeys.seedDefinitions().filter { it.aiEnabled }.map { it.key }

/**
 * A scripted [AiEditRun]. Whatever the key, its scope is [documents] minus what it has [processed];
 * a batch reports [progress], waits for [gate] when one is set, answers [batch] and marks that
 * batch's rows processed; an apply answers [outcome]; what landed so far is [landed]. It records
 * every key, request and applied batch.
 */
internal class FakeAiEditRun : AiEditRun {
    val documents = MutableStateFlow<List<DocListItem>>(emptyList())
    override val processed = MutableStateFlow(emptySet<String>())

    /** Every key the scope was read with, latest last. */
    val keys = mutableListOf<ScopeKey>()

    /** Every request a batch was asked with. */
    val requests = mutableListOf<AiEditRequest>()

    /** Every set of rows handed to [apply], as they stood. */
    val applied = mutableListOf<List<ReviewDoc>>()

    var progress: List<EditProgress> = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    var batch: BatchResult = BatchResult.Empty
    var outcome: ApplyOutcome = ApplyOutcome.NothingSelected

    /** What [landedSoFar] answers. */
    var landed: ApplyResult = ApplyResult(0, 0, moreRemain = false)

    /** What [moreRemain] answers. */
    var moreLeft: Boolean = false

    /** Thrown by [moreRemain] when set. */
    var moreRemainFailure: Throwable? = null

    /** Thrown by [remainingDocs] when set, as a failed read of the scope would be. */
    var remainingFailure: Throwable? = null

    /** Put documents with these ids in scope. */
    fun inScope(vararg docIds: String) {
        documents.value = docIds.map { listItem(it) }
    }

    override fun docsFor(key: ScopeKey): Flow<List<DocListItem>> {
        keys += key
        return documents
    }

    override suspend fun moreRemain(key: ScopeKey): Boolean {
        moreRemainFailure?.let { throw it }
        return moreLeft
    }

    override suspend fun remainingDocs(key: ScopeKey): List<DocListItem> {
        keys += key
        remainingFailure?.let { throw it }
        return documents.value.filterNot { it.doc.docId in processed.value }
    }

    override fun markHandedOff(docIds: List<String>) {
        processed.update { it + docIds }
    }

    override suspend fun suggestNextBatch(
        key: ScopeKey,
        request: AiEditRequest,
        onProgress: (EditProgress) -> Unit,
    ): BatchResult {
        keys += key
        requests += request
        progress.forEach(onProgress)
        gate?.await()
        val answer = batch
        if (answer is BatchResult.Review) {
            processed.update { done -> done + (answer.docs + answer.emptyDocs).map { it.docId } }
        }
        return answer
    }

    override suspend fun apply(
        key: ScopeKey,
        docs: List<ReviewDoc>,
    ): ApplyOutcome {
        applied += docs
        return outcome
    }

    override fun landedSoFar(): ApplyResult = landed
}

/**
 * A row as a run returns it, with the pre-select policy applied: [tags] are additions from the
 * vocabulary, [newTags] invented ones, [removals] current tags to drop and [fields] suggested
 * values, each replacing the value [current] gives for its key.
 */
internal fun reviewDoc(
    docId: String,
    tags: List<String> = emptyList(),
    newTags: List<String> = emptyList(),
    removals: List<String> = emptyList(),
    fields: Map<String, String> = emptyMap(),
    current: Map<String, String> = emptyMap(),
): ReviewDoc =
    ReviewDoc(
        docId = docId,
        title = null,
        fileName = "$docId.pdf",
        snippet = null,
        chips =
            tags.map { ReviewChip(it, isNew = false, selected = false) } +
                newTags.map { ReviewChip(it, isNew = true, selected = false) },
        removals = removals.map { ReviewChip(it, isNew = false, selected = false) },
        fields = fields.map { (key, value) -> ReviewField(key, key, value, current = current[key], selected = false) },
    ).recommended()

/** A reviewed batch: [docs] with suggestions, [empty] without, and [failures] not answered. */
internal fun review(
    vararg docs: ReviewDoc,
    empty: List<ReviewDoc> = emptyList(),
    failures: Int = 0,
): BatchResult.Review = BatchResult.Review(docs.toList(), empty, failures, total = docs.size + empty.size + failures)

/**
 * A viewmodel over [run], pinned to the foreground unless a test says otherwise. Left to itself
 * the background default depends on the scope size, which is not what most of these tests are
 * about; the ones that *are* pass `background = null` and let it decide.
 *
 * The task defaults to [EditTask.CUSTOM], the everything-on preset, because a page with no task
 * chosen deliberately configures nothing. Preset behaviour has its own suite in [AiEditTaskTest].
 */
internal fun vm(
    run: FakeAiEditRun = FakeAiEditRun(),
    session: FakeLibrarySession = FakeLibrarySession(),
    settings: FakeSettingsRepository = FakeSettingsRepository(),
    selection: List<String> = emptyList(),
    background: Boolean? = false,
    task: EditTask? = EditTask.CUSTOM,
    saved: SavedStateHandle = SavedStateHandle(),
) = AiEditViewModel(session, settings, selection, saved, run).also { model ->
    task?.let(model::setTask)
    background?.let(model::setBackground)
}
