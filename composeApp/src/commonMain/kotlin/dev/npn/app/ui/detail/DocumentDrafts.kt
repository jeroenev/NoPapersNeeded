package dev.npn.app.ui.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.npn.core.app.DocListItem
import dev.npn.core.app.MetadataWriter
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import dev.npn.core.util.NpnJson
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
 * True when [value] can be written to a field of [type]. Blank counts: it clears the field. Shared
 * by the save bar's guard and each editor's error state so the two can never disagree.
 */
internal fun isWritable(
    type: FieldType,
    value: String?,
): Boolean = FieldValues.canonicalize(type, value) is FieldParse.Ok

@Serializable
internal data class DocumentDraft(
    /** Edited field values by registry key; null clears the field. */
    val fields: Map<String, String?> = emptyMap(),
    /** The type each edited key was edited as, so validity can be judged without the registry. */
    val types: Map<String, FieldType> = emptyMap(),
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

    /** Edited keys whose value the field's type refuses; a save is blocked while any remain. */
    val invalidKeys: Set<String>
        get() = fields.keys.filterTo(LinkedHashSet()) { !isWritable(types[it] ?: FieldType.TEXT, fields[it]) }

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

    /**
     * Record an edit to one field. [persisted] is what the library already holds: an edit that
     * lands back on it — typing and undoing, or picking the value that was already there — leaves
     * no draft behind. That keeps the save bar and the resume banner reporting real changes only,
     * and stops a save from publishing a journal event that changes nothing (the journal is
     * append-only and syncs to every device, so no-op events are not free). A null [value] clears.
     */
    fun field(
        docId: String,
        key: String,
        value: String?,
        persisted: String?,
        type: FieldType = FieldType.TEXT,
    ) {
        edit(docId) { draft ->
            if (value.normalized() == persisted.normalized()) {
                draft.copy(fields = draft.fields - key, types = draft.types - key)
            } else {
                draft.copy(fields = draft.fields + (key to value), types = draft.types + (key to type))
            }
        }
    }

    /**
     * Drop a single field from the draft. Accepting an AI suggestion for a field means abandoning
     * whatever the user typed there, so its draft entry must go — otherwise [DocumentDraft.overlay]
     * would keep showing the typed value and a later save would overwrite the accepted suggestion
     * with it. Routes through [edit] so the prune-when-settled rule holds.
     */
    fun clearField(
        docId: String,
        key: String,
    ) {
        edit(docId) { it.copy(fields = it.fields - key, types = it.types - key) }
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
     * from being swallowed.
     */
    private fun settle(
        docId: String,
        written: Map<String, String?>,
    ) {
        mutate { all ->
            val latest = all.getValue(docId)
            val unwritten = latest.fields.filter { (key, value) -> key !in written || written[key] != value }
            val types = latest.types.filterKeys { it in unwritten }
            all + (docId to latest.copy(fields = unwritten, types = types, saving = false, saved = true))
        }
    }

    fun save(
        docId: String,
        reviewed: Boolean? = null,
        onSaved: () -> Unit = {},
    ) {
        val captured = drafts.value[docId] ?: DocumentDraft()
        if (captured.saving || !captured.writable) return
        edit(docId) { it.copy(saving = true) }
        scope.launch {
            // [scope] is the app scope, which is not the main thread; the writer is main-safe either
            // way. State mutation, by contrast, is main-thread-only by construction (the plain
            // mutableStateOf relies on it), so every touch of [drafts] hops to Main.
            var finished = false
            val outcome =
                try {
                    runCatchingCancellable {
                        // Every edited field in one transaction: a failure retains the whole draft.
                        // The reviewed marker is deliberately last, after the fields have succeeded.
                        if (captured.fields.isNotEmpty()) writer.applyEdits(listOf(docId), fields = captured.fields)
                        if (reviewed != null) writer.setReviewed(listOf(docId), reviewed)
                        withContext(Dispatchers.Main) { settle(docId, captured.fields) }
                    }.also { finished = true }
                } finally {
                    // Cancelled: clear the saving flag even while cancelling, so the draft is not stuck.
                    if (!finished) {
                        withContext(NonCancellable + Dispatchers.Main) { edit(docId) { it.copy(saving = false) } }
                    }
                }
            outcome.onFailure { error ->
                withContext(Dispatchers.Main) {
                    mutate { all ->
                        all + (docId to all.getValue(docId).copy(saving = false, error = error.message.orEmpty()))
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
