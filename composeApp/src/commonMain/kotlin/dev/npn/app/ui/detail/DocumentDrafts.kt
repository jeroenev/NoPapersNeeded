package dev.npn.app.ui.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.npn.core.app.DocListItem
import dev.npn.core.app.MetadataWriter
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import dev.npn.core.util.NpnJson
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.seconds

/** How long the save bar keeps confirming a save before the settled draft is dropped. */
internal val SavedNoticeDuration = 3.seconds

/**
 * True when [value], read in canonical form, can be written to a field of [type]. Blank counts: it
 * clears the field. This is not the editor's verdict: a number is typed the way the user's locale
 * writes it, so only the editor can tell that `12.34` is none where a comma is the decimal point.
 * That verdict travels as [DocumentDraft.refused]; this check is for what reaches a draft otherwise,
 * such as a draft saved by a build that kept no refusals.
 */
private fun isWritable(
    type: FieldType,
    value: String?,
): Boolean = FieldValues.canonicalize(type, value) is FieldParse.Ok

@Serializable
internal data class DocumentDraft(
    /** Edited field values by registry key; null clears the field. A [refused] key holds what was typed. */
    val fields: Map<String, String?> = emptyMap(),
    /** The type each edited key was edited as, so validity can be judged without the registry. */
    val types: Map<String, FieldType> = emptyMap(),
    /**
     * Edited keys whose editor refused what was typed, judged the way the user writes numbers. Their
     * value in [fields] is the entry as typed, never written.
     */
    val refused: Set<String> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val dirty: Boolean get() = fields.isNotEmpty()

    /**
     * Nothing left to write, in flight or worth reporting. [DocumentDrafts] drops settled entries
     * so a document the user merely visited never counts as one with unsaved changes.
     */
    val settled: Boolean get() = !dirty && !saving && error == null

    /** Edited keys the editor refused, or whose value the field's type refuses; a save is blocked while any remain. */
    val invalidKeys: Set<String>
        get() =
            fields.keys.filterTo(LinkedHashSet()) {
                it in refused || !isWritable(types[it] ?: FieldType.TEXT, fields[it])
            }

    val writable: Boolean get() = invalidKeys.isEmpty()

    /** The item as it will read once saved: draft values over persisted ones, cleared keys absent. */
    fun overlay(item: DocListItem): DocListItem {
        val merged = LinkedHashMap(item.doc.fields)
        for ((key, value) in fields) {
            val normalized = value?.trim()?.ifEmpty { null }
            if (normalized == null) merged.remove(key) else merged[key] = normalized
        }
        return item.copy(doc = item.doc.copy(fields = merged))
    }
}

/**
 * Library-scoped drafts survive route and preview changes. The host saves their serialized form
 * through rememberSaveable on Android. Writes use the app scope so leaving a screen cannot cancel
 * an explicitly requested save. Only edited fields are written, preserving concurrent edits.
 *
 * The draft map lives in Compose snapshot state, not a flow: a text field is fully controlled off
 * it, so a keystroke has to be visible to the very next composition. A [MutableStateFlow] round
 * trip resolves a dispatch later, leaving the field one frame behind its own input — which drops
 * and reorders characters under fast typing and Android IMEs. Every mutation runs on the main
 * thread (see [save]), so the plain [mutableStateOf] needs no cross-thread atomicity.
 */
internal class DocumentDrafts(
    private val writer: MetadataWriter,
    private val scope: CoroutineScope,
    restored: String? = null,
) {
    private val drafts =
        mutableStateOf(
            // A draft written by an older build (a property this version no longer knows) or a
            // truncated bundle must not take the app down on restore: losing an unsaved draft is
            // recoverable, a crash on every restore of that saved state is not.
            restored
                ?.let { runCatching { NpnJson.Persisted.decodeFromString<Map<String, DocumentDraft>>(it) }.getOrNull() }
                .orEmpty(),
        )

    /**
     * The current drafts. Read inside composition it is snapshot-tracked; read from an event
     * handler it is a plain untracked read, which is all callers outside composition need.
     */
    val state: Map<String, DocumentDraft> get() = drafts.value

    /**
     * How many editors are showing each document. Counted, not a set: during a navigation
     * transition both the outgoing and the incoming screen are composed, and they can be editing
     * the same document — expanding the wide layout's preview panel into the full-screen detail
     * route does exactly that. The incoming editor registers before the outgoing one disposes, so
     * a set would be emptied by that dispose and leave the surviving editor unlisted for good.
     */
    private val editing = mutableStateOf<Map<String, Int>>(emptyMap())

    /**
     * Documents whose editor is on screen right now. The resume banner subtracts these so it never
     * offers to open a draft the user is already looking at (full-screen detail or the wide
     * layout's side preview panel), regardless of which route is showing.
     */
    val onScreen: Set<String> get() = editing.value.keys

    /** Register/unregister an editor from a [androidx.compose.runtime.DisposableEffect]. */
    fun registerEditing(docId: String) {
        editing.value = editing.value + (docId to (editing.value[docId] ?: 0) + 1)
    }

    fun unregisterEditing(docId: String) {
        val remaining = (editing.value[docId] ?: 0) - 1
        editing.value = if (remaining > 0) editing.value + (docId to remaining) else editing.value - docId
    }

    fun serialize(): String =
        NpnJson.Persisted.encodeToString(
            drafts.value
                .filterValues {
                    it.dirty
                }.mapValues { it.value.copy(saving = false) },
        )

    /** Each document's save in flight, from [save] until it lands ([settle]) or does not. */
    private val inFlight = HashMap<String, InFlightSave>()

    /**
     * A save in flight: the values it [writes], by key. An accepted suggestion also keeps the draft
     * as it was [before] the write started, to tell the typing it replaces from typing done since.
     */
    private class InFlightSave(
        val writes: Map<String, String?>,
        val before: DocumentDraft? = null,
    ) {
        /** Its keys the user has put back to the stored value since: no change if it does not land. */
        val reverted = HashSet<String>()
    }

    /** Reserve the document before a suggestion write starts, using the same edit rules as Save. */
    fun beginAcceptance(
        docId: String,
        key: String,
        value: String,
    ): Boolean {
        val draft = state[docId]
        if (draft?.saving == true || draft?.writable == false) return false
        edit(docId) { it.copy(saving = true) }
        inFlight[docId] = InFlightSave(mapOf(key to value), before = draft)
        return true
    }

    /**
     * Release the reservation even if the accepting viewmodel was cleared before its write ran. Once
     * the suggestion [accepted], the draft entry it replaces goes too.
     */
    fun finishAcceptance(
        docId: String,
        accepted: Boolean,
    ) {
        if (accepted) {
            val accepting = inFlight[docId]
            accepting?.writes?.keys?.forEach { clearField(docId, it, accepting.before) }
            settle(docId, accepting?.writes.orEmpty())
            edit(docId) { it.copy(saving = false) }
        } else {
            edit(docId) { unlanded(docId, it) }
        }
    }

    /**
     * Record an edit to one field. [persisted] is what the library already holds: an edit that
     * lands back on it — typing and undoing, or picking the value that was already there — leaves
     * no draft behind. That keeps the save bar and the resume banner reporting real changes only,
     * and stops a save from publishing a journal event that changes nothing (the journal is
     * append-only and syncs to every device, so no-op events are not free). A null [value] clears.
     *
     * While a save of this field runs, the library is about to hold what that save writes instead,
     * so an edit is no change only when it is none either way. Any other stays, for [settle] to keep
     * when it differs from what the save wrote: going back to the stored value while the save writes
     * another is an edit like any other once the save lands.
     *
     * [refused] says the editor refused [value], as typed: it blocks the save until a valid edit
     * replaces it. A refused entry is no value, so it is an edit even where it reads like a stored
     * value of the type (`12.34` refused under a comma locale); only an off-type stored value,
     * typed back as it is, is no change.
     */
    fun field(
        docId: String,
        key: String,
        value: String?,
        persisted: String?,
        type: FieldType = FieldType.TEXT,
        refused: Boolean = false,
    ) {
        fun isNoChangeFrom(stored: String?) =
            value.normalized() == stored.normalized() &&
                (!refused || FieldValues.canonicalize(type, stored) is FieldParse.Invalid)
        val asStored = isNoChangeFrom(persisted)
        val saving = inFlight[docId]?.takeIf { key in it.writes }
        saving?.reverted?.let { if (asStored) it += key else it -= key }
        val unchanged = asStored && (saving == null || isNoChangeFrom(saving.writes[key]))
        edit(docId) { draft ->
            if (unchanged) {
                draft.copy(fields = draft.fields - key, types = draft.types - key, refused = draft.refused - key)
            } else {
                draft.copy(
                    fields = draft.fields + (key to value),
                    types = draft.types + (key to type),
                    refused = if (refused) draft.refused + key else draft.refused - key,
                )
            }
        }
    }

    /**
     * Drop a single field from the draft. Accepting an AI suggestion for a field means abandoning
     * whatever the user typed there, so its draft entry must go once the write lands — otherwise [DocumentDraft.overlay]
     * would keep showing the typed value and a later save would overwrite the accepted suggestion
     * with it. Keeps an edit made since [captured], while the write ran. Routes through [edit] so
     * the prune-when-settled rule holds.
     */
    private fun clearField(
        docId: String,
        key: String,
        captured: DocumentDraft?,
    ) {
        val latest = state[docId] ?: return
        if (latest.fields.containsKey(key) != (captured?.fields?.containsKey(key) == true) ||
            latest.fields[key] != captured?.fields?.get(key)
        ) {
            return
        }
        if (latest.types[key] != captured?.types?.get(key) ||
            (key in latest.refused) != (captured?.refused?.contains(key) == true)
        ) {
            return
        }
        edit(docId) { it.copy(fields = it.fields - key, types = it.types - key, refused = it.refused - key) }
    }

    fun discard(docId: String) {
        if (drafts.value[docId]?.saving != true) mutate { it - docId }
    }

    /**
     * Drop an entry that has nothing left to say. Called once the save bar's "saved" notice has
     * been on screen long enough: without it every document edited this session would keep an
     * entry, and the notice would stay up until the next edit.
     */
    fun forget(docId: String) {
        mutate { all -> if (all[docId]?.settled == true) all - docId else all }
    }

    /** Read-modify-write on the snapshot state. Callers must be on the main thread. */
    private fun mutate(change: (Map<String, DocumentDraft>) -> Map<String, DocumentDraft>) {
        drafts.value = change(drafts.value)
    }

    private fun edit(
        docId: String,
        change: (DocumentDraft) -> DocumentDraft,
    ) {
        mutate { all ->
            val current = all[docId] ?: DocumentDraft()
            val next = change(current).copy(saved = false, error = null)
            if (next.settled) all - docId else all + (docId to next)
        }
    }

    /**
     * Mark the save done, keeping only the field edits made *while* it ran — comparing against
     * [written] rather than clearing the map wholesale is what stops a keystroke landing mid-save
     * from being swallowed. The comparison is the normalized one [field] echoes with, so a value
     * differing from what landed only in whitespace is no edit either. A refused entry is always
     * such an edit (a draft with one never saves), even one typed exactly like the value just
     * written.
     */
    private fun settle(
        docId: String,
        written: Map<String, String?>,
    ) {
        inFlight.remove(docId)
        mutate { all ->
            val latest = all.getValue(docId)
            val unwritten =
                latest.fields.filter { (key, value) ->
                    key in latest.refused || key !in written || written[key].normalized() != value.normalized()
                }
            val types = latest.types.filterKeys { it in unwritten }
            all + (docId to latest.copy(fields = unwritten, types = types, saving = false, saved = true))
        }
    }

    /**
     * [draft] once its save ended without landing: the library still holds what it held, so a field
     * put back to that while the save ran is no edit after all.
     */
    private fun unlanded(
        docId: String,
        draft: DocumentDraft,
    ): DocumentDraft {
        val reverted = inFlight.remove(docId)?.reverted.orEmpty()
        return draft.copy(
            fields = draft.fields - reverted,
            types = draft.types - reverted,
            refused = draft.refused - reverted,
            saving = false,
        )
    }

    fun save(
        docId: String,
        reviewed: Boolean? = null,
        onSaved: () -> Unit = {},
    ) {
        val captured = drafts.value[docId] ?: DocumentDraft()
        if (captured.saving || !captured.writable) return
        edit(docId) { it.copy(saving = true) }
        inFlight[docId] = InFlightSave(captured.fields)
        scope.launch {
            // [scope] is the app scope, which is not the main thread; the writer is main-safe either
            // way. State mutation, by contrast, is main-thread-only by construction (the plain
            // mutableStateOf relies on it), so every touch of [drafts] hops to Main.
            var finished = false
            var fieldsLanded = false
            val outcome =
                try {
                    runCatchingCancellable {
                        // Every edited field in one transaction: a failure retains the whole draft.
                        // The reviewed marker is deliberately last, after the fields have succeeded.
                        if (captured.fields.isNotEmpty()) writer.applyEdits(listOf(docId), fields = captured.fields)
                        fieldsLanded = true
                        if (reviewed != null) writer.setReviewed(listOf(docId), reviewed)
                        withContext(Dispatchers.Main) { settle(docId, captured.fields) }
                    }.also { finished = true }
                } finally {
                    // Cancelled: clear the saving flag even while cancelling, so the draft is not stuck.
                    @Suppress("SuspendFunInFinallySection") // NonCancellable, which the rule misses beside Main.
                    if (!finished) {
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (fieldsLanded) settle(docId, captured.fields) else edit(docId) { unlanded(docId, it) }
                        }
                    }
                }
            outcome.onFailure { error ->
                // NonCancellable, as the [finally] above: a cancellation must not skip this fixup
                // and strand the draft at `saving`, un-saveable and un-discardable.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (fieldsLanded) settle(docId, captured.fields)
                    mutate { all ->
                        val latest = all.getValue(docId)
                        val remaining = if (fieldsLanded) latest else unlanded(docId, latest)
                        all + (docId to remaining.copy(saved = false, error = error.reason()))
                    }
                }
                return@launch
            }
            // Deliberately outside the try: callers navigate from here, and a navigation that
            // throws must not be reported to the user as a failed save — the write already landed.
            withContext(Dispatchers.Main) { onSaved() }
            // "Saved" is a notice, not a state: drop the entry once it has had time to be read, so
            // it does not linger under the document for the rest of the session. Owned by the save
            // coroutine (not the UI) so it fires even when the user has already left, e.g. "Mark
            // reviewed & next". [forget] refuses a non-settled entry, so an edit made during the
            // wait is safe.
            delay(SavedNoticeDuration)
            withContext(Dispatchers.Main) { forget(docId) }
        }
    }
}

/** The normalization the journal writers apply, so "unchanged" means the same on both sides. */
private fun String?.normalized(): String? = this?.trim()?.ifEmpty { null }

internal val LocalDocumentDrafts = staticCompositionLocalOf<DocumentDrafts> { error("Document drafts not provided") }
