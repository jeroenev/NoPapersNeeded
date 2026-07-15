package dev.npn.app.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
) : ViewModel() {

    val tags: StateFlow<List<TagInfo>> =
        session.tags.observeTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Build the namespace tree from the flat tag list. */
    fun buildTree(flat: List<TagInfo>): List<TagNode> {
        val byPath = flat.associateBy { it.name }
        // Collect all path prefixes so intermediate namespaces appear even without their own tag.
        val allPaths = sortedSetOf<String>()
        for (t in flat) {
            val parts = t.name.split('.')
            val acc = StringBuilder()
            for ((i, p) in parts.withIndex()) {
                if (i > 0) acc.append('.')
                acc.append(p)
                allPaths.add(acc.toString())
            }
        }

        fun childrenOf(prefix: String, depth: Int): List<TagNode> {
            val prefixDot = if (prefix.isEmpty()) "" else "$prefix."
            val direct = allPaths.filter { path ->
                path != prefix &&
                    path.startsWith(prefixDot) &&
                    !path.removePrefix(prefixDot).contains('.')
            }.sorted()

            return direct.map { path ->
                val kids = childrenOf(path, depth + 1)
                val info = byPath[path]
                val own = info?.count ?: 0
                TagNode(
                    path = path,
                    segment = path.substringAfterLast('.'),
                    depth = depth,
                    info = info,
                    count = own,
                    descendantCount = own + kids.sumOf { it.descendantCount },
                    children = kids,
                )
            }
        }

        return childrenOf("", 0)
    }

    fun create(name: String, color: String?, icon: String?) = launch {
        session.meta.setTagStyle(name.trim(), color, icon)
    }

    fun rename(from: String, to: String) = launch { session.meta.renameTag(from, to.trim()) }
    fun merge(from: String, into: String) = launch { session.meta.mergeTag(from, into.trim()) }
    fun delete(name: String) = launch { session.meta.deleteTag(name) }
    fun setStyle(name: String, color: String?, icon: String?) = launch {
        session.meta.setTagStyle(name, color, icon)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}
