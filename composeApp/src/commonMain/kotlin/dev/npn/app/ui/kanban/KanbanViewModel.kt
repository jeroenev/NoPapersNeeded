package dev.npn.app.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.KanbanGrouping
import dev.npn.core.model.DocStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A kanban lane and the documents that belong in it. */
data class KanbanColumn(
    val id: String,
    val title: String,
    val cards: List<DocListItem>,
)

/**
 * Kanban board. v1 supports two grouping modes:
 * - STATUS (default): three fixed lanes (inbox / reviewed / filed), moves set the doc status.
 * - TAGS: lanes come from a configurable tag namespace (e.g. "status"), one lane per direct child
 *   tag under the namespace plus an "unassigned" lane. Moves add the target tag and remove the
 *   others in the same namespace.
 *
 * Moves are single journal events (conflict-free per PLAN.md). Drag is deferred; the card menu is
 * the v1 move affordance.
 */
class KanbanViewModel(
    val session: LibrarySession,
) : ViewModel() {

    data class Config(
        val grouping: KanbanGrouping = KanbanGrouping.STATUS,
        val tagNamespace: String = DEFAULT_TAG_NAMESPACE,
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val tagNames: StateFlow<List<String>> =
        session.tags.observeTags()
            .map { tags -> tags.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allDocs: StateFlow<List<DocListItem>> =
        session.documents.observeDocuments(LibraryFilter(), LibrarySort())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val columns: StateFlow<List<KanbanColumn>> =
        combine(allDocs, _config, tagNames) { docs, cfg, tags ->
            buildColumns(docs, cfg, tags)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setGrouping(grouping: KanbanGrouping) {
        _config.value = _config.value.copy(grouping = grouping)
    }

    fun setTagNamespace(ns: String) {
        _config.value = _config.value.copy(tagNamespace = ns.trim().ifEmpty { DEFAULT_TAG_NAMESPACE })
    }

    /** Move [docId] to the lane identified by [columnId] under the current config. */
    fun moveTo(docId: String, columnId: String, currentTags: List<String>) {
        val cfg = _config.value
        viewModelScope.launch {
            runCatching {
                when (cfg.grouping) {
                    KanbanGrouping.STATUS -> {
                        val status = DocStatus.entries.firstOrNull { it.wire == columnId } ?: return@runCatching
                        session.meta.setStatus(listOf(docId), status)
                    }
                    else -> {
                        val prefix = cfg.tagNamespace + "."
                        // Remove other tags in the namespace, then add the target (unless "unassigned").
                        currentTags.filter { it.startsWith(prefix) }.forEach { session.meta.removeTag(listOf(docId), it) }
                        if (columnId != UNASSIGNED) session.meta.addTag(listOf(docId), columnId)
                    }
                }
            }
        }
    }

    private fun buildColumns(docs: List<DocListItem>, cfg: Config, allTagNames: List<String>): List<KanbanColumn> =
        when (cfg.grouping) {
            KanbanGrouping.STATUS -> DocStatus.entries.map { status ->
                KanbanColumn(
                    id = status.wire,
                    title = statusTitle(status),
                    cards = docs.filter { DocStatus.fromWire(it.doc.status) == status },
                )
            }
            else -> {
                val prefix = cfg.tagNamespace + "."
                // Lane order from tag definitions: direct children of the namespace, sorted.
                val lanes = allTagNames
                    .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('.') }
                    .distinct()
                    .sorted()
                val tagLanes = lanes.map { tag ->
                    KanbanColumn(
                        id = tag,
                        title = tag.removePrefix(prefix),
                        cards = docs.filter { tag in it.tags },
                    )
                }
                val unassigned = KanbanColumn(
                    id = UNASSIGNED,
                    title = "Unassigned",
                    cards = docs.filter { item -> item.tags.none { it.startsWith(prefix) } },
                )
                tagLanes + unassigned
            }
        }

    private fun statusTitle(s: DocStatus): String = when (s) {
        DocStatus.INBOX -> "Inbox"
        DocStatus.REVIEWED -> "Reviewed"
        DocStatus.FILED -> "Filed"
    }

    companion object {
        const val DEFAULT_TAG_NAMESPACE = "status"
        const val UNASSIGNED = "__unassigned__"
    }
}
