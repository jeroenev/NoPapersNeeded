package dev.npn.app.ui.tags

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A node in the hierarchical tag tree. Dot-separated tag paths ("a.b.c") form a namespace tree.
 * Intermediate nodes may not be real tags (no [TagInfo]); [count] is the direct doc count of this
 * exact path, [descendantCount] the sum including children.
 */
data class TagNode(
    val path: String,
    val segment: String,
    val depth: Int,
    val info: TagInfo?,
    val count: Int,
    val descendantCount: Int,
    val children: List<TagNode>,
)

class TagManagementViewModel(
    session: LibrarySession,
) : MessagesViewModel() {
    private val tagAdmin = session.tagAdmin

    val tags: StateFlow<List<TagInfo>> =
        session.tags
            .observeTags()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun rename(
        from: String,
        to: String,
    ) = launchReporting(UiAction.RENAME_TAG) { tagAdmin.renameTag(from, to.trim()) }

    fun merge(
        from: String,
        into: String,
    ) = launchReporting(UiAction.MERGE_TAGS) { tagAdmin.mergeTag(from, into.trim()) }

    /**
     * Merge every selected tag into [into] (the primary the user picked). Documents holding any
     * of the other tags end up with [into] instead; the emptied tags are then auto-pruned.
     */
    fun mergeAll(
        names: List<String>,
        into: String,
    ) = launchReporting(UiAction.MERGE_TAGS) {
        names.filter { it != into }.forEach { tagAdmin.mergeTag(it, into) }
    }

    fun delete(name: String) = launchReporting(UiAction.DELETE_TAG) { tagAdmin.deleteTag(name) }

    /** Bulk delete for the selection mode; one report covers the whole batch. */
    fun deleteAll(names: List<String>) =
        launchReporting(UiAction.DELETE_TAG) {
            names.forEach { tagAdmin.deleteTag(it) }
        }

    fun setStyle(
        name: String,
        color: String?,
        icon: String?,
    ) = launchReporting(UiAction.UPDATE_TAG_STYLE) {
        tagAdmin.setTagStyle(name, color, icon)
    }

    /** Apply one color to every selected tag; one report covers the whole batch. */
    fun setStyleAll(
        names: List<String>,
        color: String?,
    ) = launchReporting(UiAction.UPDATE_TAG_STYLE) {
        names.forEach { tagAdmin.setTagStyle(it, color, null) }
    }
}
