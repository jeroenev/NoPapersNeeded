package dev.npn.app.ui.palette

import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.ScanResult
import dev.npn.app.ui.common.ImportRequest
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.asUiText
import dev.npn.app.ui.common.importBatch
import dev.npn.app.ui.common.importScanned
import dev.npn.app.ui.common.offerSearchTip
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.TagInfo
import dev.npn.core.fs.ExternalSource
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.util.runLogged
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_show
import no_papers_needed.composeapp.generated.resources.palette_import_failed
import no_papers_needed.composeapp.generated.resources.palette_sync_failed
import no_papers_needed.composeapp.generated.resources.queue_sync_not_configured
import no_papers_needed.composeapp.generated.resources.queue_sync_unavailable

/** What the palette's overlay asks of it: the query, the keyboard, and running a result. */
internal interface CommandPaletteActions {
    fun onQueryChange(text: String)

    /** Move the selection by [delta] results, clamped to the result list. */
    fun moveSelection(delta: Int)

    fun runSelected()

    fun run(item: PaletteItem)

    fun close()
}

private class UiBits(
    val open: Boolean,
    val selected: Int,
)

private class LibrarySources(
    val documents: List<DocListItem>,
    val tags: List<TagInfo>,
    val views: List<SavedView>,
)

private class StaticSources(
    val commands: List<PaletteCommand>,
    val settings: List<PaletteSettingsEntry>,
    val recents: List<String>,
)

/**
 * The command palette: one searchable overlay over every screen. It searches documents through the
 * library's own repository (full text and file names), the library's tags and saved views, the
 * settings panes and the common actions, and runs a chosen result through the existing screens'
 * navigation and facades. Recent choices lead an empty query.
 *
 * Documents the repository returns are kept even when neither their title nor their file name
 * matches: the full-text index may have found them by their contents, and their order still puts
 * the strongest title matches first.
 */
internal class CommandPaletteViewModel(
    private val session: LibrarySession,
    private val settings: SettingsRepository,
    private var navigation: PaletteNavigation,
) : MessagesViewModel(),
    CommandPaletteActions {
    override val log get() = session.log

    private val open = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow(0)
    private val recents = MutableStateFlow<List<String>>(emptyList())
    private val commands = MutableStateFlow<List<PaletteCommand>>(emptyList())
    private val settingsEntries = MutableStateFlow<List<PaletteSettingsEntry>>(emptyList())
    private val documents = MutableStateFlow<List<DocListItem>>(emptyList())

    private val tags = session.tags.observeTags().stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())
    private val views =
        session.savedViews
            .observeSavedViews()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val requestChannel = Channel<PaletteRequest>(Channel.UNLIMITED)

    /** What the palette asks the composable's platform launchers to start. */
    val requests: Flow<PaletteRequest> = requestChannel.receiveAsFlow()

    private var searchJob: Job? = null

    private val uiBits = combine(open, selected, ::UiBits)

    private val librarySources = combine(documents, tags, views, ::LibrarySources)

    private val staticSources = combine(commands, settingsEntries, recents, ::StaticSources)

    val state: StateFlow<PaletteState> =
        combine(uiBits, query, librarySources, staticSources) { bits, text, library, static ->
            val groups = groups(text, library, static)
            val count = groups.sumOf { it.items.size }
            PaletteState(
                open = bits.open,
                query = text,
                groups = groups,
                selected = if (count == 0) 0 else bits.selected.coerceIn(0, count - 1),
            )
        }.stateIn(viewModelScope, WhileSubscribedTimeout, PaletteState())

    fun bindNavigation(navigation: PaletteNavigation) {
        this.navigation = navigation
    }

    /** The commands and settings panes the current language and platform offer, from the screen. */
    fun setSources(
        commands: List<PaletteCommand>,
        settings: List<PaletteSettingsEntry>,
    ) {
        this.commands.value = commands
        settingsEntries.value = settings
    }

    /** Open the palette on an empty query, its first result selected. */
    fun open() {
        query.value = ""
        selected.value = 0
        open.value = true
        searchDocuments("")
    }

    fun toggle() {
        if (open.value) close() else open()
    }

    override fun onQueryChange(text: String) {
        query.value = text
        selected.value = 0
        searchDocuments(text)
    }

    override fun moveSelection(delta: Int) {
        val count = state.value.items.size
        if (count == 0) return
        selected.value = (state.value.selected + delta).coerceIn(0, count - 1)
    }

    override fun runSelected() {
        val current = state.value
        if (current.query == query.value) current.selectedItem?.let(::run)
    }

    override fun run(item: PaletteItem) {
        // Enter auto-repeat must not run a second result after the first closed the overlay.
        if (!open.value) return
        remember(item.key)
        when (val target = item.target) {
            is PaletteTarget.Doc -> {
                close()
                navigation.openDocument(target.docId)
            }

            is PaletteTarget.Tag -> {
                close()
                navigation.openLibrary(LibraryFilter(tagsAll = listOf(target.name)))
            }

            is PaletteTarget.View -> {
                close()
                navigation.openSavedView(target.view)
            }

            is PaletteTarget.Settings -> {
                close()
                navigation.openSettings(target.id, target.revealAdvanced)
            }

            is PaletteTarget.Action -> {
                runAction(target.id)
            }
        }
    }

    private fun runAction(id: PaletteCommandId) {
        close()
        when (id) {
            PaletteCommandId.IMPORT -> {
                requestChannel.trySend(PaletteRequest.PickFiles)
            }

            PaletteCommandId.SCAN -> {
                requestChannel.trySend(PaletteRequest.Scan)
            }

            // The watched folder is set up where it is explained, in Settings.
            PaletteCommandId.WATCH_FOLDER -> {
                navigation.openSettings(SettingsCategoryId.IMPORT)
            }

            PaletteCommandId.SYNC_NOW -> {
                launchReporting(Res.string.palette_sync_failed) {
                    when (session.sync.syncNow()) {
                        is SyncOutcome.Unavailable -> message(uiText(Res.string.queue_sync_unavailable))
                        SyncOutcome.NotConfigured -> message(uiText(Res.string.queue_sync_not_configured))
                        is SyncOutcome.Completed, is SyncOutcome.Failed, SyncOutcome.AlreadyRunning -> Unit
                    }
                }
            }

            PaletteCommandId.ASSISTANT -> {
                navigation.openAssistant()
            }

            PaletteCommandId.INBOX -> {
                navigation.openReviewQueue()
            }

            PaletteCommandId.TAGS -> {
                navigation.openTags()
            }

            PaletteCommandId.SETTINGS -> {
                navigation.openSettings(null)
            }
        }
    }

    override fun close() {
        open.value = false
        query.value = ""
        selected.value = 0
        searchJob?.cancel()
        documents.value = emptyList()
    }

    /** Import the files the platform picker returned, and say what became of them. */
    fun importFiles(files: List<Pair<ExternalSource, String>>) {
        if (files.isEmpty()) return
        close()
        launchReporting(Res.string.palette_import_failed) {
            val batch = session.ingest.importBatch(ImportRequest(files))
            message(
                batch.asUiText(),
                batch.imported.takeIf { it.isNotEmpty() }?.let { ids ->
                    UiMessage.Action(uiText(Res.string.library_show)) {
                        navigation.openLibrary(LibraryFilter(docIds = ids))
                    }
                },
            )
            viewModelScope.launch {
                log.runLogged("The search tip") {
                    offerSearchTip(settings, session.documents, batch.imported, ::message)
                }
            }
        }
    }

    /** Import what a platform scan came to, and say what became of it. */
    fun importScan(result: ScanResult) =
        launchReporting(Res.string.palette_import_failed) {
            session.ingest.importScanned(result, ::message)
        }

    /** Remember [key] as the most recent choice; the overlay keeps at most [MAX_RECENTS]. */
    private fun remember(key: String) {
        recents.update { current -> (listOf(key) + current.filter { it != key }).take(MAX_RECENTS) }
    }

    /**
     * Search the library for [text]. A blank query reads the documents chosen before, so an empty
     * palette leads with what the user last picked; typing goes through the repository's full-text
     * and file-name search, debounced and bounded.
     */
    private fun searchDocuments(text: String) {
        searchJob?.cancel()
        documents.value = emptyList()
        if (!open.value) return
        searchJob =
            viewModelScope.launch {
                if (text.isBlank()) {
                    documents.value = recentDocuments()
                } else {
                    delay(SEARCH_DEBOUNCE_MS)
                    documents.value =
                        log
                            .runLogged("Searching the library from the palette") {
                                session.documents
                                    .observeDocuments(LibraryFilter(query = text), LibrarySort())
                                    .first()
                                    .take(MAX_DOCUMENT_CANDIDATES)
                            }.orEmpty()
                }
            }
    }

    /** The documents the palette was used on before, in the order they were chosen. */
    private suspend fun recentDocuments(): List<DocListItem> {
        val ids =
            recents.value
                .filter { it.startsWith(DOCUMENT_PREFIX) }
                .map { it.removePrefix(DOCUMENT_PREFIX) }
                .take(MAX_RECENTS)
        if (ids.isEmpty()) return emptyList()
        val found =
            log
                .runLogged("Reading the palette's recent documents") {
                    session.documents.liveDocuments(ids)
                }.orEmpty()
        val byId = found.associateBy { it.doc.docId }
        return ids.mapNotNull(byId::get)
    }

    private fun groups(
        text: String,
        library: LibrarySources,
        static: StaticSources,
    ): List<PaletteGroup> {
        val q = text.trim()
        val documents = library.documents.map { it.toPaletteItem(q) }
        val tags = library.tags.map { it.toPaletteItem() }
        val views = library.views.map { it.toPaletteItem() }
        val settings = settingsItems(q, static.settings, static.recents)
        val commands = static.commands.map { it.toPaletteItem() }

        if (q.isEmpty()) {
            val all =
                (documents + tags + views + settings + commands).associateBy { it.key }
            val recent =
                static.recents
                    .mapNotNull(all::get)
                    .distinctBy { it.key }
                    .take(PALETTE_GROUP_LIMIT)
            val recentKeys = recent.map { it.key }.toSet()
            val rest =
                listOf(
                    PaletteGroupKind.DOCUMENTS to documents,
                    PaletteGroupKind.TAGS to tags,
                    PaletteGroupKind.VIEWS to views,
                    PaletteGroupKind.SETTINGS to settings,
                    PaletteGroupKind.ACTIONS to commands,
                ).map { (kind, items) ->
                    PaletteGroup(kind, items.filterNot { it.key in recentKeys }.take(PALETTE_GROUP_LIMIT))
                }
            return (listOf(PaletteGroup(PaletteGroupKind.RECENT, recent)) + rest)
                .filter { it.items.isNotEmpty() }
        }

        val rankedDocuments =
            rankPalette(
                query = q,
                items = library.documents,
                recents = static.recents,
                includeUnmatched = true,
                key = { documentPaletteKey(it.doc.docId) },
                text = { "${it.doc.displayTitle} ${it.doc.fileName}" },
            ).map { it.toPaletteItem(q) }
        val rankedTags =
            rankPalette(
                query = q,
                items = library.tags,
                recents = static.recents,
                key = { tagPaletteKey(it.name) },
                text = { it.name },
            ).map { it.toPaletteItem() }
        val rankedViews =
            rankPalette(
                query = q,
                items = library.views,
                recents = static.recents,
                key = { viewPaletteKey(it.id) },
                text = { it.name },
            ).map { it.toPaletteItem() }
        val rankedCommands =
            rankPalette(
                query = q,
                items = static.commands,
                recents = static.recents,
                key = { actionPaletteKey(it.id) },
                text = { it.title },
            ).map { it.toPaletteItem() }
        return listOf(
            PaletteGroup(PaletteGroupKind.DOCUMENTS, rankedDocuments),
            PaletteGroup(PaletteGroupKind.TAGS, rankedTags),
            PaletteGroup(PaletteGroupKind.VIEWS, rankedViews),
            PaletteGroup(PaletteGroupKind.SETTINGS, settings),
            PaletteGroup(PaletteGroupKind.ACTIONS, rankedCommands),
        ).filter { it.items.isNotEmpty() }
    }

    private fun settingsItems(
        query: String,
        entries: List<PaletteSettingsEntry>,
        recents: List<String>,
    ): List<PaletteItem> {
        val recentIndex = recents.withIndex().associate { it.value to it.index }
        if (query.isEmpty()) {
            return entries
                .sortedWith(
                    compareBy(
                        { recentIndex[settingsPaletteKey(it.id)] ?: Int.MAX_VALUE },
                        { entries.indexOf(it) },
                    ),
                ).map { it.toPaletteItem() }
        }
        val scored =
            entries.mapNotNull { entry ->
                val titleScore = paletteMatchScore(query, entry.title)
                val keywords =
                    entry.keywords.map { it to paletteMatchScore(query, it) }.filter { it.second > 0 }
                val bestKeyword = keywords.maxByOrNull { it.second }
                val score = maxOf(titleScore, bestKeyword?.second ?: 0)
                if (score == 0) {
                    null
                } else {
                    val byTitle = titleScore >= (bestKeyword?.second ?: 0)
                    PaletteSettingsMatch(
                        item =
                            entry.toPaletteItem(
                                subtitle = if (byTitle) null else bestKeyword?.first,
                                revealAdvanced =
                                    !byTitle &&
                                        bestKeyword?.first?.let { it in entry.advancedKeywords } == true,
                            ),
                        score = score,
                        recent = recentIndex[settingsPaletteKey(entry.id)] ?: Int.MAX_VALUE,
                        index = entries.indexOf(entry),
                    )
                }
            }
        return scored
            .sortedWith(
                compareByDescending<PaletteSettingsMatch> { it.score }
                    .thenBy { it.recent }
                    .thenBy { it.index },
            ).take(PALETTE_GROUP_LIMIT)
            .map { it.item }
    }

    private class PaletteSettingsMatch(
        val item: PaletteItem,
        val score: Int,
        val recent: Int,
        val index: Int,
    )

    private fun DocListItem.toPaletteItem(query: String): PaletteItem {
        val matchedTitle =
            query.isBlank() || paletteMatchScore(query, "${doc.displayTitle} ${doc.fileName}") > 0
        return PaletteItem(
            key = documentPaletteKey(doc.docId),
            title = doc.displayTitle,
            target = PaletteTarget.Doc(doc.docId),
            subtitle = doc.relPath,
            // A full-text hit whose title does not match says why it is here.
            snippet =
                if (matchedTitle) {
                    null
                } else {
                    snippet
                        ?.replace('\u0001', ' ')
                        ?.replace('\u0002', ' ')
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                },
        )
    }

    private fun TagInfo.toPaletteItem(): PaletteItem =
        PaletteItem(
            key = tagPaletteKey(name),
            title = name,
            target = PaletteTarget.Tag(name),
            count = count,
        )

    private fun SavedView.toPaletteItem(): PaletteItem =
        PaletteItem(
            key = viewPaletteKey(id),
            title = name,
            target = PaletteTarget.View(this),
        )

    private fun PaletteCommand.toPaletteItem(): PaletteItem =
        PaletteItem(
            key = actionPaletteKey(id),
            title = title,
            target = PaletteTarget.Action(id),
        )

    private fun PaletteSettingsEntry.toPaletteItem(
        subtitle: String? = null,
        revealAdvanced: Boolean = false,
    ): PaletteItem =
        PaletteItem(
            key = settingsPaletteKey(id),
            title = title,
            target = PaletteTarget.Settings(id, revealAdvanced),
            subtitle = subtitle,
        )

    private companion object {
        const val MAX_RECENTS = 12
        const val MAX_DOCUMENT_CANDIDATES = 24
        const val SEARCH_DEBOUNCE_MS = 120L
        const val DOCUMENT_PREFIX = "doc:"
    }
}
