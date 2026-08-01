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
    val session: LibrarySession,
) : MessagesViewModel() {
    val tags: StateFlow<List<TagInfo>> =
        session.tags
            .observeTags()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun create(
        name: String,
        color: String?,
        icon: String?,
    ) = launchReporting(UiAction.CREATE_TAG) {
        session.meta.setTagStyle(name.trim(), color, icon)
    }

    fun rename(
        from: String,
        to: String,
    ) = launchReporting(UiAction.RENAME_TAG) { session.meta.renameTag(from, to.trim()) }

    fun merge(
        from: String,
        into: String,
    ) = launchReporting(UiAction.MERGE_TAGS) { session.meta.mergeTag(from, into.trim()) }

    fun delete(name: String) = launchReporting(UiAction.DELETE_TAG) { session.meta.deleteTag(name) }

    fun setStyle(
        name: String,
        color: String?,
        icon: String?,
    ) = launchReporting(UiAction.UPDATE_TAG_STYLE) {
        session.meta.setTagStyle(name, color, icon)
    }
}
