package dev.npn.app.ui.tags

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_tags_color_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_delete_failed
import no_papers_needed.composeapp.generated.resources.common_tags_delete_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_merge_failed
import no_papers_needed.composeapp.generated.resources.common_tags_merge_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_rename_failed
import no_papers_needed.composeapp.generated.resources.common_tags_style_failed
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * A node in the hierarchical tag tree. Dot-separated tag paths ("a.b.c") form a namespace tree.
 * Intermediate nodes may not be real tags (no [TagInfo]); [descendantCount] is the number of
 * documents carrying this exact path or any path under it.
 */
data class TagNode(
    val path: String,
    val segment: String,
    val info: TagInfo?,
    val descendantCount: Int,
    val children: List<TagNode>,
)

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

/** The tags screen: the library's tags, and their [TagWrites]. */
class TagManagementViewModel(
    session: LibrarySession,
) : MessagesViewModel(),
    TagWrites {
    private val tagAdmin = session.tagAdmin

    val tags: StateFlow<List<TagInfo>> =
        session.tags
            .observeTags()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    override fun rename(
        from: String,
        to: String,
    ) = launchReporting(Res.string.common_tags_rename_failed) { tagAdmin.renameTag(from, to.trim()) }

    override fun merge(
        names: List<String>,
        into: String,
    ) {
        val kept = into.trim()
        eachTag(
            names.filter { it != kept },
            Res.string.common_tags_merge_failed,
            Res.plurals.common_tags_merge_some_failed,
        ) { tagAdmin.mergeTag(it, kept) }
    }

    override fun delete(names: List<String>) =
        eachTag(names, Res.string.common_tags_delete_failed, Res.plurals.common_tags_delete_some_failed) {
            tagAdmin.deleteTag(it)
        }

    override fun setStyle(
        names: List<String>,
        color: String?,
    ) = eachTag(names, Res.string.common_tags_style_failed, Res.plurals.common_tags_color_some_failed) {
        tagAdmin.setTagStyle(it, color, null)
    }

    /**
     * Run [write] for each of [names], carrying on past the ones that fail, as Trash's bulk actions
     * do. A failure of the only tag says why ([oneFailed]); failures among several are counted
     * ([someFailed], "X of Y").
     */
    private fun eachTag(
        names: List<String>,
        oneFailed: StringResource,
        someFailed: PluralStringResource,
        write: suspend (String) -> Unit,
    ) {
        if (names.isEmpty()) return
        viewModelScope.launch {
            val failures = names.mapNotNull { name -> runCatchingCancellable { write(name) }.exceptionOrNull() }
            when {
                failures.isEmpty() -> Unit
                names.size == 1 -> message(uiText(oneFailed, failures.single().reasonText()))
                else -> message(uiText(someFailed, names.size, failures.size, names.size))
            }
        }
    }
}
