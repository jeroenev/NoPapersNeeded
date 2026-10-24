package dev.npn.app.ui.ai

import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.fitsUiState
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_finish_failed
import org.jetbrains.compose.resources.StringResource

/**
 * What leaving a Review without applying actually does, which differs between the two flows and
 * must not be worded as if it did not: the interactive run keeps its scope and can suggest a next
 * batch, while a queued run is the only copy of an answer already paid for and is destroyed.
 */
enum class ReviewExit {
    /** Interactive run: this batch is skipped, its documents stay reviewed, the scope remains. */
    SKIP_BATCH,

    /** Queued run: the stored suggestions are discarded and the run leaves the processing queue. */
    DISCARD_RUN,
}

/**
 * One batch under Review: per-document suggestion chips and field rows awaiting confirmation
 * ([docs]), and the documents the model had nothing for ([emptyDocs]). Nothing is written yet.
 */
data class ReviewBatchState(
    val docs: List<ReviewDoc>,
    val emptyDocs: List<ReviewDoc>,
)

/**
 * Everything the user can do to a Review batch. Both AI-edit viewmodels implement it, the
 * interactive run and a queued run opened from the processing queue, so the batch renders and
 * behaves identically wherever it appears.
 */
interface ReviewController {
    /** What [leaveReview] does to the suggestions. */
    val reviewExit: ReviewExit

    /** Tag additions and removals never share a tag, so one toggle covers both lists. */
    fun toggleChip(
        docId: String,
        tag: String,
    )

    fun toggleField(
        docId: String,
        key: String,
    )

    /** Select or deselect every suggestion of one document. */
    fun setDocSelection(
        docId: String,
        selected: Boolean,
    )

    /** Select or deselect every suggestion of every document. */
    fun setAllSelection(selected: Boolean)

    /** Reset the batch to the domain's pre-select policy; see [selectRecommended]. */
    fun selectRecommended()

    /** Write the selected suggestions. */
    fun apply()

    /** Leave the Review without writing anything; see [reviewExit]. */
    fun leaveReview()
}

/**
 * The selection half of [ReviewController], written once for both AI-edit viewmodels over the
 * batch that [updateReview] reaches.
 */
abstract class ReviewViewModel :
    MessagesViewModel(),
    ReviewController {
    /** Apply [transform] to the batch under review; a no-op while there is none. */
    protected abstract fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>)

    final override fun toggleChip(
        docId: String,
        tag: String,
    ) = updateReview { it.toggleChip(docId, tag) }

    final override fun toggleField(
        docId: String,
        key: String,
    ) = updateReview { it.toggleField(docId, key) }

    final override fun setDocSelection(
        docId: String,
        selected: Boolean,
    ) = updateReview { it.setDocSelection(docId, selected) }

    final override fun setAllSelection(selected: Boolean) = updateReview { it.setAllSelected(selected) }

    final override fun selectRecommended() = updateReview { it.selectRecommended() }

    /**
     * Write [batch]'s selected suggestions through [write] and show what that came to: Done with
     * what landed (a [ApplyOutcome.Done.followUpFailure] reported with [followUpFailed]), or the
     * Review again — what a Retry still owes, said as such, or the batch as it was when nothing was
     * selected or the Apply threw, which is reported.
     */
    protected suspend fun applyReview(
        batch: ReviewBatchState,
        followUpFailed: StringResource,
        write: suspend (List<ReviewDoc>) -> ApplyOutcome,
        showReview: (ReviewBatchState) -> Unit,
        showDone: (ApplyResult) -> Unit,
    ) {
        var outcome: ApplyOutcome? = null
        reporting(Res.string.ai_edit_apply_finish_failed) { outcome = write(batch.docs) }
        when (val applied = outcome) {
            null, ApplyOutcome.NothingSelected -> {
                showReview(batch)
            }

            is ApplyOutcome.Done -> {
                applied.followUpFailure?.let { message(uiText(followUpFailed, it.reasonText())) }
                showDone(applied.result)
            }

            is ApplyOutcome.Retry -> {
                message(applied.failureText())
                showReview(batch.copy(docs = applied.remaining))
            }
        }
    }
}

/** The tag additions and removals of this document that are ticked. */
internal val ReviewDoc.selectedTagCount: Int
    get() = chips.count { it.selected } + removals.count { it.selected }

/** The field values of this document that are ticked. */
internal val ReviewDoc.selectedFieldCount: Int
    get() = fields.count { it.selected }

/** Every ticked suggestion of this document. */
internal val ReviewDoc.selectedCount: Int
    get() = selectedTagCount + selectedFieldCount

/** Every suggestion of this document, ticked or not. */
internal val ReviewDoc.suggestionCount: Int
    get() = chips.size + removals.size + fields.size

/**
 * Selection toggling over a Review batch, as pure list transforms.
 *
 * Which suggestions a run *starts* selected is domain policy and lives in core; which ones the user
 * then ticks is presentation state, the same for both AI-edit viewmodels ([ReviewViewModel]).
 */
internal fun List<ReviewDoc>.toggleChip(
    docId: String,
    tag: String,
): List<ReviewDoc> =
    mapDoc(docId) { doc ->
        // Tag additions and removals never share a tag, so one toggle covers both lists.
        doc.copy(chips = doc.chips.flipChip(tag), removals = doc.removals.flipChip(tag))
    }

internal fun List<ReviewDoc>.toggleField(
    docId: String,
    key: String,
): List<ReviewDoc> =
    mapDoc(docId) { doc ->
        doc.copy(fields = doc.fields.map { if (it.key == key) it.copy(selected = !it.selected) else it })
    }

/** Select or deselect every suggestion of one document. */
internal fun List<ReviewDoc>.setDocSelection(
    docId: String,
    selected: Boolean,
): List<ReviewDoc> = mapDoc(docId) { it.withAllSelected(selected) }

/** Select or deselect every suggestion of every document. */
internal fun List<ReviewDoc>.setAllSelected(selected: Boolean): List<ReviewDoc> = map { it.withAllSelected(selected) }

/**
 * Reset every document to the domain's pre-select policy ([ReviewDoc.recommended]), which is what
 * makes it a safe bulk action: "select all" also ticks the three kinds of suggestion the policy
 * deliberately leaves off (invented tags, tag removals, values that would replace something), so
 * offering it as *the* prominent shortcut would quietly undo the one guard rail this screen has.
 */
internal fun List<ReviewDoc>.selectRecommended(): List<ReviewDoc> = map { it.recommended() }

private fun List<ReviewDoc>.mapDoc(
    docId: String,
    transform: (ReviewDoc) -> ReviewDoc,
): List<ReviewDoc> = map { if (it.docId == docId) transform(it) else it }

/**
 * What a Review saves of its ticks for a restored process ([of]), and how a reopened run gets them
 * back ([restore]). Saved as a list of strings ([serializer]):
 *
 * - The first names the base, what every suggestion is ticked as unless an entry says otherwise:
 *   `opened`, the run's own selection as it opens (its pre-select policy over the documents as they
 *   are then), `all` or `none`.
 * - Each further one is a document whose ticks differ from the base: its id, then each such
 *   suggestion as the state it is to have (`1` ticked, `0` not), its kind (`+` a tag addition, `-` a
 *   tag removal, `=` a field) and the tag or field key, all separated by U+001F, a control character
 *   no id, tag or key holds.
 *
 * An entry is a state, never a flip of the base: a default that changed between saving and
 * restoring (another device filled the field the user unticked, or removed the tag) cannot turn
 * what the user refused into what Apply writes. The base is whichever of the three needs the fewest
 * characters, so unticking a few suggestions of a large run, deselecting all, or a large partial
 * Apply followed by a Retry each stay small. A document of the run no longer under review (applied
 * already) is saved with none of its suggestions ticked. A selection whose smallest form still does
 * not fit the saved state ([fitsUiState]) is saved as `none` alone, and so is restored with nothing
 * ticked: a restored Review never ticks what the user may have taken off.
 */
internal object SavedTicks {
    val serializer: KSerializer<List<String>> = ListSerializer(String.serializer())

    /** The saved form of [docs]' ticks, the run having opened as [opened]. */
    fun of(
        docs: List<ReviewDoc>,
        opened: List<ReviewDoc>,
    ): List<String> {
        val ticks = ticksOf(docs, opened)
        val smallest =
            Base.entries
                .map { base -> encode(base, ticks) }
                .minBy { saved -> saved.sumOf { it.length + ENTRY_OVERHEAD } }
        return if (fitsUiState(serializer, smallest)) smallest else listOf(Base.NONE.marker)
    }

    /** [reopened], the run as it opens again, ticked as [saved] says ([of]). */
    fun restore(
        reopened: List<ReviewDoc>,
        saved: List<String>,
    ): List<ReviewDoc> {
        // Nothing else this build saved: nothing ticked, rather than the run's own selection.
        val base = Base.entries.firstOrNull { it.marker == saved.firstOrNull() } ?: Base.NONE
        val wanted =
            saved.drop(1).associate { entry ->
                val parts = entry.split(SEPARATOR)
                parts.first() to parts.drop(1).filter { it.length > 1 }.associate { it.substring(1) to (it[0] == TICKED) }
            }
        return reopened.map { doc ->
            val states = wanted[doc.docId].orEmpty()

            fun ticked(
                tick: String,
                opened: Boolean,
            ): Boolean = states[tick] ?: base.ticks(opened)
            doc.copy(
                chips = doc.chips.map { it.copy(selected = ticked(tick(ADDED, it.tag), it.selected)) },
                removals = doc.removals.map { it.copy(selected = ticked(tick(REMOVED, it.tag), it.selected)) },
                fields = doc.fields.map { it.copy(selected = ticked(tick(FIELD, it.key), it.selected)) },
            )
        }
    }

    private enum class Base(
        val marker: String,
    ) {
        OPENED("opened"),
        NONE("none"),
        ALL("all"),
        ;

        /** What this base ticks a suggestion the run [opened] ticked or not as. */
        fun ticks(opened: Boolean): Boolean =
            when (this) {
                OPENED -> opened
                NONE -> false
                ALL -> true
            }
    }

    /** One suggestion of one document: whether it is ticked [now], and whether the run [opened] with it ticked. */
    private class Tick(
        val docId: String,
        val tick: String,
        val now: Boolean,
        val opened: Boolean,
    )

    /** Every suggestion of [docs] and [opened], a document no longer in [docs] with nothing ticked. */
    private fun ticksOf(
        docs: List<ReviewDoc>,
        opened: List<ReviewDoc>,
    ): List<Tick> {
        val wasById = opened.associateBy { it.docId }
        val nowById = docs.associateBy { it.docId }
        return (opened.map { it.docId } + docs.map { it.docId }).distinct().flatMap { docId ->
            val was = wasById[docId]?.tickStates().orEmpty()
            val now = nowById[docId]?.tickStates().orEmpty()
            (was.keys + now.keys).map { tick -> Tick(docId, tick, now[tick] == true, was[tick] == true) }
        }
    }

    private fun encode(
        base: Base,
        ticks: List<Tick>,
    ): List<String> =
        listOf(base.marker) +
            ticks
                .filter { it.now != base.ticks(it.opened) }
                .groupBy { it.docId }
                .map { (docId, differing) ->
                    (listOf(docId) + differing.map { "${if (it.now) TICKED else UNTICKED}${it.tick}" })
                        .joinToString(SEPARATOR.toString())
                }

    /** Each suggestion of this document, as its kind and what it names, and whether it is ticked. */
    private fun ReviewDoc.tickStates(): Map<String, Boolean> =
        chips.associate { tick(ADDED, it.tag) to it.selected } +
            removals.associate { tick(REMOVED, it.tag) to it.selected } +
            fields.associate { tick(FIELD, it.key) to it.selected }

    private fun tick(
        kind: Char,
        what: String,
    ): String = "$kind$what"

    private const val SEPARATOR = '\u001F'
    private const val TICKED = '1'
    private const val UNTICKED = '0'
    private const val ADDED = '+'
    private const val REMOVED = '-'
    private const val FIELD = '='

    /** What JSON adds to each string of the list: its quotes and the comma after it. */
    private const val ENTRY_OVERHEAD = 3
}

private fun List<ReviewChip>.flipChip(tag: String): List<ReviewChip> =
    map { if (it.tag == tag) it.copy(selected = !it.selected) else it }

private fun ReviewDoc.withAllSelected(selected: Boolean): ReviewDoc =
    copy(
        chips = chips.map { it.copy(selected = selected) },
        removals = removals.map { it.copy(selected = selected) },
        fields = fields.map { it.copy(selected = selected) },
    )
