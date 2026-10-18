package dev.npn.app.ui.tags

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.BulkFailure
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiState
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_tags_color_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_delete_failed
import no_papers_needed.composeapp.generated.resources.common_tags_delete_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_merge_failed
import no_papers_needed.composeapp.generated.resources.common_tags_merge_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_rename_failed
import no_papers_needed.composeapp.generated.resources.common_tags_style_failed

/**
 * One card of the tags screen. Dot-separated tag paths ("a.b.c") form a namespace tree, which the
 * screen shows flattened depth-first ([tagRows]). An intermediate namespace need not be a real tag
 * (no [info]); [descendantCount] is the number of documents carrying this exact path or any path
 * under it.
 */
data class TagRow(
    val path: String,
    val segment: String,
    val info: TagInfo?,
    val descendantCount: Int,
)

/**
 * The namespace tree of [tags], depth-first with siblings in alphabetical order: every tag, plus a
 * row for each namespace prefix one of them implies.
 */
internal fun tagRows(tags: List<TagInfo>): List<TagRow> {
    val byPath = tags.associateBy { it.name }
    // Every path (real tags plus the prefixes they imply), sorted, with the documents on or under it.
    val paths = sortedSetOf<String>()
    val totals = HashMap<String, Int>()
    for (tag in tags) {
        var end = -1
        do {
            end = tag.name.indexOf('.', end + 1).let { if (it < 0) tag.name.length else it }
            val prefix = tag.name.substring(0, end)
            paths.add(prefix)
            totals[prefix] = (totals[prefix] ?: 0) + tag.count
        } while (end < tag.name.length)
    }
    val childrenByParent = paths.groupBy { it.substringBeforeLast('.', "") }
    val rows = ArrayList<TagRow>(paths.size)

    fun walk(parent: String) {
        for (path in childrenByParent[parent].orEmpty()) {
            rows += TagRow(path, path.substringAfterLast('.'), byPath[path], totals.getValue(path))
            walk(path)
        }
    }
    walk("")
    return rows
}

/**
 * What the tags screen writes. Every action but rename takes a list of tags: a card's own menu
 * passes one, the selection passes all of it, and both go through the same write.
 */
interface TagWrites {
    fun rename(
        from: String,
        to: String,
    )

    /**
     * Merge every one of [names] into [into], the tag the user picked to keep. Documents holding
     * any of them end up with [into] instead; the emptied tags are then pruned.
     */
    fun merge(
        names: List<String>,
        into: String,
    )

    fun delete(names: List<String>)

    /** Give every one of [names] the same [color]. */
    fun setStyle(
        names: List<String>,
        color: String?,
    )
}

/**
 * The tags screen's multi-select. Only real tags (rows with a [TagInfo]) are selectable;
 * namespace-only rows carry no journal state of their own.
 */
interface TagSelectionActions {
    fun toggleSelected(name: String)

    /** Select every tag in the library. */
    fun selectAll()

    /** Select [name] alone, dropping the rest of the selection. */
    fun selectOnly(name: String)

    fun clearSelection()
}

/**
 * The one dialog the tags screen has open, or none. Every action but rename names a list of tags: a
 * card's own menu one, the selection all of it. [TagManagementViewModel.dialog] keeps it, so it is
 * open again after the screen is recreated.
 */
@Serializable
internal sealed interface TagDialog {
    @Serializable
    data class Rename(
        val name: String,
    ) : TagDialog

    @Serializable
    data class Merge(
        val names: List<String>,
    ) : TagDialog

    @Serializable
    data class Style(
        val names: List<String>,
    ) : TagDialog

    @Serializable
    data class Delete(
        val names: List<String>,
    ) : TagDialog
}

/** The tags screen: the library's tags, its selection of them, and their [TagWrites]. */
class TagManagementViewModel(
    session: LibrarySession,
    /** What the screen's back-stack entry saves for a restored process; a new one opens it afresh. */
    saved: SavedStateHandle = SavedStateHandle(),
) : MessagesViewModel(),
    TagWrites,
    TagSelectionActions {
    private val tagAdmin = session.tagAdmin
    override val log = session.log

    /** Null until the first load, so the screen does not flash "no tags" before it. */
    val tags: StateFlow<List<TagInfo>?> =
        session.tags
            .observeTags()
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    private val selected = saved.uiState(SELECTION_KEY, emptyList<String>())

    /** [tags] as the screen's cards: the namespace tree, flattened depth-first. Null until the first load. */
    val rows: StateFlow<List<TagRow>?> =
        tags
            .map { it?.let(::tagRows) }
            .onEach { rows -> if (rows != null) selected.update { rows.selectable(it) } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * The selected tags, in card order. Tags can disappear underneath a selection (another device,
     * auto-pruning after edits): the selection forgets them once the rows load without them, so a
     * tag of the same name coming back later is not selected again.
     */
    val selection: StateFlow<List<String>> = selected.flow

    private val dialogState = saved.uiState<TagDialog?>(DIALOG_KEY, null)

    /** The dialog the screen has open; see [TagDialog]. */
    internal val dialog: StateFlow<TagDialog?> = dialogState.flow

    internal fun openDialog(dialog: TagDialog?) = dialogState.set(dialog)

    override fun toggleSelected(name: String) =
        selected.update { current -> ordered(if (name in current) current - name else current + name) }

    override fun selectAll() {
        selected.set(rows.value.orEmpty().selectable())
    }

    override fun selectOnly(name: String) {
        selected.set(listOf(name))
    }

    override fun clearSelection() {
        selected.set(emptyList())
    }

    /** [names] in card order, as far as the rows are loaded. */
    private fun ordered(names: List<String>): List<String> = rows.value?.selectable(names) ?: names

    // Names arrive trimmed: the dialogs they are typed or picked in trim what they confirm.

    override fun rename(
        from: String,
        to: String,
    ) = launchReporting(Res.string.common_tags_rename_failed) { tagAdmin.renameTag(from, to) }

    override fun merge(
        names: List<String>,
        into: String,
    ) = eachTag(names.filter { it != into }, MergeFailed) { tagAdmin.mergeTag(it, into) }

    override fun delete(names: List<String>) =
        eachTag(names, DeleteFailed) {
            tagAdmin.deleteTag(it)
        }

    override fun setStyle(
        names: List<String>,
        color: String?,
    ) = eachTag(names, StyleFailed) {
        tagAdmin.setTagStyle(it, color, null)
    }

    /** Run [write] for each of [names], carrying on past the ones that fail, reported as [failed] says. */
    private fun eachTag(
        names: List<String>,
        failed: BulkFailure,
        write: suspend (String) -> Unit,
    ) {
        if (names.isEmpty()) return
        viewModelScope.launch { forEachReporting(names, failed, write) }
    }

    /** The real tags among these rows, in order; only those among [among], if given. */
    private fun List<TagRow>.selectable(among: Collection<String>? = null): List<String> =
        mapNotNull { row -> row.info?.name?.takeIf { among == null || it in among } }

    private companion object {
        val MergeFailed = BulkFailure(Res.string.common_tags_merge_failed, Res.plurals.common_tags_merge_some_failed)
        val DeleteFailed = BulkFailure(Res.string.common_tags_delete_failed, Res.plurals.common_tags_delete_some_failed)
        val StyleFailed = BulkFailure(Res.string.common_tags_style_failed, Res.plurals.common_tags_color_some_failed)
        const val SELECTION_KEY = "tags.selection"
        const val DIALOG_KEY = "tags.dialog"
    }
}
