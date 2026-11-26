package dev.npn.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import dev.npn.app.LibraryUi
import dev.npn.app.LibraryViewModelHost
import dev.npn.app.platform.offersKeyboardShortcutList
import dev.npn.app.ui.ai.AiChatScreen
import dev.npn.app.ui.ai.AiEditReviewScreen
import dev.npn.app.ui.ai.AiEditScreen
import dev.npn.app.ui.common.OpenDocumentRequests
import dev.npn.app.ui.common.PublishWindowTitle
import dev.npn.app.ui.common.reducedMotion
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.app.ui.detail.BrowseContext
import dev.npn.app.ui.detail.DocDetailScreen
import dev.npn.app.ui.detail.WithDraftResumeBanner
import dev.npn.app.ui.library.LibraryHandoffs
import dev.npn.app.ui.library.LibraryScreen
import dev.npn.app.ui.palette.CommandPaletteActions
import dev.npn.app.ui.palette.CommandPaletteHost
import dev.npn.app.ui.palette.CommandPaletteViewModel
import dev.npn.app.ui.palette.PaletteNavigation
import dev.npn.app.ui.queue.QueueScreen
import dev.npn.app.ui.reminders.ReminderChecks
import dev.npn.app.ui.reminders.UpcomingScreen
import dev.npn.app.ui.rules.AutoMatchRulesScreen
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsScreen
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.trash.TrashScreen
import dev.npn.app.ui.update.WithUpdateBanner
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SavedView
import dev.npn.core.index.WriteBlock
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.sync.SyncStatus
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_rules_title
import no_papers_needed.composeapp.generated.resources.reminders_upcoming_title
import org.jetbrains.compose.resources.stringResource

/**
 * The open library's screens: the navigation shell around the [library]'s nav graph, with the
 * banners that sit above every screen. [queue] is the shell's Activity indicator; [snackbar] is the
 * shell's own, for what is reported outside any one screen. [writeBlock] is why the library refuses
 * writes now, if it does, and [writeBlockActions] the ways out of it. Where the platform offers it,
 * `?` and Ctrl+/ open the keyboard shortcut list from anywhere in the shell, and Ctrl/Cmd+K (or the
 * rail's search button) opens the command palette.
 */
@Composable
internal fun MainNav(
    settings: SettingsRepository,
    session: LibrarySession,
    /** The library's navigation, the browse order the detail screen pages through, and its hand-offs. */
    library: LibraryUi,
    aiConfigured: Boolean,
    queue: QueueStatus,
    writeBlock: WriteBlock?,
    snackbar: SnackbarHostState,
    onOpenLibrary: (location: String) -> Unit,
    writeBlockActions: WriteBlockActions,
    palette: CommandPaletteViewModel =
        viewModel(viewModelStoreOwner = library.viewModels) {
            CommandPaletteViewModel(session, settings, ShellPaletteNavigation(library))
        },
) {
    val nav = library.nav
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val topLevel = destination.topLevelDestination()
    val assistant = destination.isAssistant()

    PublishWindowTitle(windowTitle(destination, topLevel, assistant))
    val syncStatus by remember(session) { session.sync.status }.collectAsStateWithLifecycle(SyncStatus())
    val libraryLocation = remember(session) { settings.settings.value.libraryLocation }
    libraryLocation?.let { location ->
        // With the app lock on, a notification names nothing in the library.
        ReminderChecks(session, location) { settings.settings.value.appLock.enabled }
    }
    LaunchedEffect(session, nav) {
        OpenDocumentRequests.upcoming.arrivals.collect { location ->
            if (location == libraryLocation) nav.navigate(Route.Upcoming) { launchSingleTop = true }
        }
    }
    LaunchedEffect(session, nav) {
        OpenDocumentRequests.documents.arrivals.collect { request ->
            if (!request.belongsTo(libraryLocation)) return@collect
            nav.openDoc(
                request.docId,
                library.browse,
                options = {
                    popUpTo(Route.Library)
                    launchSingleTop = true
                },
            )
        }
    }
    var shortcutsOpen by rememberSaveable { mutableStateOf(false) }
    val openShortcuts: (() -> Unit)? = if (offersKeyboardShortcutList()) ({ shortcutsOpen = true }) else null
    val shellFocus = remember { FocusRequester() }
    var shellFocusHeld by remember { mutableStateOf(false) }
    WithUpdateBanner(settings, snackbar) {
        WithWriteBlockBanner(writeBlock, writeBlockActions) {
            WithCloudSyncBanner(
                status = syncStatus,
                onOpenSettings = { nav.navigate(Route.Settings(SettingsCategoryId.CLOUD_SYNC.routeKey)) },
            ) {
                WithDraftResumeBanner(snackbar, onOpen = { docId -> nav.resumeDraft(docId, library.browse) }) {
                    LibraryViewModelHost(library) {
                        // The palette's viewmodel lives in the library's own store, so its recents
                        // and query survive a recreated window.
                        val paletteNavigation = remember(library) { ShellPaletteNavigation(library) }
                        SideEffect { palette.bindNavigation(paletteNavigation) }
                        val paletteState by palette.state.collectAsStateWithLifecycle()
                        // Not while the palette's own window is open: the two must not fight over
                        // focus as the dialog appears.
                        KeepShellFocus(shellFocus, shellFocusHeld || paletteState.open)
                        val paletteSnackbar = rememberSnackbarMessages(palette.messages)
                        Box(Modifier.fillMaxSize()) {
                            NavigationShell(
                                current = topLevel,
                                assistantCurrent = assistant,
                                aiConfigured = aiConfigured,
                                queue = queue,
                                onNavigate = { dest -> nav.navigateTopLevel(dest) },
                                // The item opens the chat; bulk edit is one segment away on its screen.
                                onAssistant = { if (!assistant) nav.navigateAi(Route.AiChat, restoreState = true) },
                                onPalette = palette::open,
                                onShortcuts = openShortcuts,
                                modifier =
                                    Modifier.shellShortcutKeys(
                                        { shellFocusHeld = it },
                                        openShortcuts,
                                        palette::toggle,
                                    ),
                            ) {
                                ShellFocusAnchor(shellFocus)
                                MainNavHost(
                                    settings = settings,
                                    session = session,
                                    library = library,
                                    aiConfigured = aiConfigured,
                                    onOpenLibrary = onOpenLibrary,
                                )
                            }
                            val paletteActions: CommandPaletteActions = palette
                            CommandPaletteHost(
                                session = session,
                                aiConfigured = aiConfigured,
                                state = paletteState,
                                actions = paletteActions,
                                requests = palette.requests,
                                onSetSources = palette::setSources,
                                onImportFiles = palette::importFiles,
                                onImportScan = palette::importScan,
                            )
                            SnackbarHost(
                                paletteSnackbar,
                                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                            )
                        }
                    }
                }
            }
        }
    }
    if (shortcutsOpen) ShortcutsDialog(onDismiss = { shortcutsOpen = false })
}

/** The shell destination [this] belongs to; null hides the rail/bottom bar. Library before the first entry. */
private fun NavDestination?.topLevelDestination(): TopLevelDestination? =
    when {
        this == null -> TopLevelDestination.LIBRARY
        hasRoute<Route.Library>() -> TopLevelDestination.LIBRARY
        hasRoute<Route.TagManagement>() -> TopLevelDestination.TAGS
        hasRoute<Route.Trash>() -> TopLevelDestination.TRASH
        hasRoute<Route.Queue>() -> TopLevelDestination.ACTIVITY
        hasRoute<Route.Settings>() -> TopLevelDestination.SETTINGS
        else -> null
    }

/** Whether [this] is one of the assistant's modes; keeps the shell visible with its item selected. */
private fun NavDestination?.isAssistant(): Boolean =
    this != null && (hasRoute<Route.AiChat>() || hasRoute<Route.AiEdit>())

/**
 * The native window title for [destination]: the label the shell gives it ([topLevel], the
 * [assistant]), the app's name on Library, and null on a document, whose screen publishes the
 * document's own title once it has loaded.
 */
@Composable
private fun windowTitle(
    destination: NavDestination?,
    topLevel: TopLevelDestination?,
    assistant: Boolean,
): String? {
    val label =
        when {
            destination?.hasRoute<Route.DocDetail>() == true -> return null

            destination?.hasRoute<Route.AutoMatchRules>() == true -> Res.string.common_rules_title

            destination?.hasRoute<Route.Upcoming>() == true -> Res.string.reminders_upcoming_title

            // The review of a background bulk edit is the assistant's, opened from Activity.
            assistant || destination?.hasRoute<Route.AiEditReview>() == true -> AssistantDestination.label

            topLevel == TopLevelDestination.LIBRARY -> Res.string.common_app_name

            else -> topLevel?.label ?: Res.string.common_app_name
        }
    return stringResource(label)
}

/**
 * Open the document a draft was left on, the way the resume banner does: back on Library with the
 * detail on top, so Back returns to the library rather than to wherever the user was.
 */
private fun NavHostController.resumeDraft(
    docId: String,
    browse: BrowseContext,
) {
    openDoc(docId, browse) {
        popUpTo(Route.Library)
        launchSingleTop = true
    }
}

/**
 * Open [docId]'s detail, paging through [order] with [query] highlighted. A document opened on its
 * own (from Activity, the assistant, a bulk edit) gets an order of just itself, so the detail never
 * shows the previous list's position, neighbours or search.
 */
private fun NavHostController.openDoc(
    docId: String,
    browse: BrowseContext,
    order: List<String> = listOf(docId),
    query: String? = null,
    options: NavOptionsBuilder.() -> Unit = {},
) {
    browse.setOrder(order, query)
    navigate(Route.DocDetail(docId), options)
}

/**
 * Runs [action] on this controller only while [entry] is the destination on top; every navigation a
 * screen starts goes through it. Otherwise a second tap lands before the first one's screen is gone:
 * a double tap on Back pops the screen beneath too (the library, leaving nothing), and a double tap
 * on a document opens it twice. Not `dropUnlessResumed`: a desktop window that is not focused only
 * starts its screens, which would drop what a screen starts on its own meanwhile, such as a trashed
 * document's detail closing, or "reviewed, next" once its save is in.
 */
private inline fun NavHostController.from(
    entry: NavBackStackEntry,
    action: NavHostController.() -> Unit,
) {
    if (currentBackStackEntry == entry) action()
}

/** Open bulk edit on [docIds], handed over through [library]; none opens it to pick its own scope. */
private fun NavHostController.openAiEdit(
    library: LibraryUi,
    docIds: List<String> = emptyList(),
) {
    library.aiEditSelection.set(docIds)
    navigateAi(Route.AiEdit)
}

/**
 * What a palette result does: the same navigation and hand-offs the screens use, so the palette
 * never reimplements a screen's behaviour. A saved view and the review preset are handed to the
 * library's viewmodel, which applies them exactly as picking them on that screen does.
 */
internal class ShellPaletteNavigation(
    private val library: LibraryUi,
) : PaletteNavigation {
    private val nav = library.nav
    private val browse = library.browse

    override fun openDocument(docId: String) {
        // Library first, so Back from the document returns to it rather than to wherever the
        // palette was opened from.
        nav.dropAiEdit()
        nav.openDoc(docId, browse) {
            popUpTo(Route.Library) { saveState = true }
            launchSingleTop = true
        }
    }

    override fun openLibrary(filter: LibraryFilter) {
        library.libraryFilter.set(filter)
        nav.navigateTopLevel(TopLevelDestination.LIBRARY)
    }

    override fun openSavedView(view: SavedView) {
        library.savedView.set(view)
        nav.navigateTopLevel(TopLevelDestination.LIBRARY)
    }

    override fun openReviewQueue() {
        library.reviewQueue.set(Unit)
        nav.navigateTopLevel(TopLevelDestination.LIBRARY)
    }

    override fun openTags() {
        nav.navigateTopLevel(TopLevelDestination.TAGS)
    }

    override fun openSettings(
        category: SettingsCategoryId?,
        revealAdvanced: Boolean,
    ) {
        nav.dropAiEdit()
        nav.navigate(Route.Settings(category?.routeKey, revealAdvanced)) {
            popUpTo(Route.Library) { saveState = true }
            launchSingleTop = true
        }
    }

    override fun openAssistant() {
        if (nav.currentBackStackEntry?.destination?.isAssistant() != true) {
            nav.navigateAi(Route.AiChat, restoreState = true)
        }
    }
}

@Composable
private fun MainNavHost(
    settings: SettingsRepository,
    session: LibrarySession,
    library: LibraryUi,
    aiConfigured: Boolean,
    onOpenLibrary: (location: String) -> Unit,
) {
    val nav = library.nav
    val browse = library.browse
    // Android's default navigation is a 700ms cross-fade, and a back swipe shrinks the page. With
    // reduced motion the destination is shown at once; otherwise the navigation library's own
    // defaults stand (desktop does not animate at all).
    val reduced = reducedMotion
    val transitions = remember(reduced) { PageTransitions(reduced) }
    NavHost(
        navController = nav,
        startDestination = Route.Library,
        enterTransition = transitions.enter,
        exitTransition = transitions.exit,
        predictivePopEnterTransition = transitions.predictivePopEnter,
        predictivePopExitTransition = transitions.predictivePopExit,
    ) {
        composable<Route.Library> { entry ->
            LibraryScreen(
                session = session,
                settings = settings,
                onOpenDoc = { docId, order, query -> nav.from(entry) { openDoc(docId, browse, order, query) } },
                onTrash = { nav.from(entry) { navigateTopLevel(TopLevelDestination.TRASH) } },
                onOpenQueue = { nav.from(entry) { navigateTopLevel(TopLevelDestination.ACTIVITY) } },
                onAiEdit =
                    if (aiConfigured) {
                        { docIds -> nav.from(entry) { openAiEdit(library, docIds) } }
                    } else {
                        null
                    },
                handoffs =
                    remember(library) {
                        LibraryHandoffs(library.libraryFilter, library.savedView, library.reviewQueue)
                    },
                onOpenSettings = { id -> nav.from(entry) { navigate(Route.Settings(id.routeKey)) } },
                onOpenUpcoming = { nav.from(entry) { navigate(Route.Upcoming) } },
                documents = library.documents,
            )
        }
        composable<Route.Upcoming> { entry ->
            UpcomingScreen(
                session = session,
                onBack = { nav.from(entry) { popBackStack() } },
                onOpenDoc = { docId -> nav.from(entry) { openDoc(docId, browse) } },
            )
        }
        composable<Route.DocDetail> { entry ->
            val route = entry.toRoute<Route.DocDetail>()
            DocDetailScreen(
                docId = route.docId,
                session = session,
                settings = settings,
                documentStores = library.documents,
                browse = browse,
                onBack = { nav.from(entry) { popBackStack() } },
                onNavigateToDoc = { targetId ->
                    nav.from(entry) {
                        // Replace the current detail route so the back stack stays "library -> detail".
                        navigate(Route.DocDetail(targetId)) {
                            popUpTo(Route.DocDetail(route.docId)) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable<Route.Trash> {
            TrashScreen(session = session)
        }
        composable<Route.TagManagement> { entry ->
            TagManagementScreen(
                session = session,
                onOpenAutomation = {
                    nav.from(entry) { navigate(Route.Settings(SettingsCategoryId.AUTOMATION.routeKey)) }
                },
            )
        }
        composable<Route.Queue> { entry ->
            QueueScreen(
                session = session,
                settings = settings,
                // A queued document is a real document; open it on its own, the way the resume banner
                // does, so Back from detail returns to Activity.
                onOpenDoc = { docId -> nav.from(entry) { openDoc(docId, browse) } },
                onOpenEditRun = { jobId -> nav.from(entry) { navigate(Route.AiEditReview(jobId)) } },
                onOpenSettings = { id -> nav.from(entry) { navigate(Route.Settings(id.routeKey)) } },
            )
        }
        settingsRoutes(nav, settings, session, library, onOpenLibrary)
        aiRoutes(library, session, settings, aiConfigured)
    }
}

/** Settings and the pages only reachable from it. */
private fun NavGraphBuilder.settingsRoutes(
    nav: NavHostController,
    settings: SettingsRepository,
    session: LibrarySession,
    library: LibraryUi,
    onOpenLibrary: (location: String) -> Unit,
) {
    val browse = library.browse
    composable<Route.Settings> { entry ->
        val route = entry.toRoute<Route.Settings>()
        SettingsScreen(
            settings = settings,
            session = session,
            outliving = library.outliving,
            category = SettingsCategoryId.fromRouteKey(route.category),
            revealAdvanced = route.revealAdvanced,
            onOpenCategory = { id, reveal -> nav.from(entry) { navigate(Route.Settings(id.routeKey, reveal)) } },
            onBack = { nav.from(entry) { popBackStack() } },
            onOpenLibrary = onOpenLibrary,
            onResumeDraft = { docId -> nav.from(entry) { resumeDraft(docId, browse) } },
            onOpenDocument = { docId -> nav.from(entry) { openDoc(docId, browse) } },
            onAutoMatchRules = { nav.from(entry) { navigate(Route.AutoMatchRules) } },
        )
    }
    composable<Route.AutoMatchRules> { entry ->
        AutoMatchRulesScreen(
            session = session,
            onBack = { nav.from(entry) { popBackStack() } },
        )
    }
}

/**
 * The assistant's two modes, the chat and bulk edit, which the shell shows as one item with a
 * segmented control between them; and the review of a background bulk edit, opened from Activity.
 */
private fun NavGraphBuilder.aiRoutes(
    library: LibraryUi,
    session: LibrarySession,
    settings: SettingsRepository,
    aiConfigured: Boolean,
) {
    val nav = library.nav
    val browse = library.browse
    composable<Route.AiChat> { entry ->
        AiChatScreen(
            session = session,
            configured = aiConfigured,
            onBack = { nav.from(entry) { popBackStack() } },
            onOpenDoc = { docId -> nav.from(entry) { openDoc(docId, browse) } },
            onShowInLibrary = { docIds ->
                nav.from(entry) {
                    library.libraryFilter.set(LibraryFilter(docIds = docIds))
                    navigateTopLevel(TopLevelDestination.LIBRARY)
                }
            },
            onAiEditDocs = { docIds -> nav.from(entry) { openAiEdit(library, docIds) } },
            onBulkEdit = { nav.from(entry) { openAiEdit(library) } },
            onOpenSettings = { id -> nav.from(entry) { navigate(Route.Settings(id.routeKey)) } },
        )
    }
    composable<Route.AiEdit> { entry ->
        // Consumed once per navigation; remembered so recomposition keeps the same hand-off.
        val selection = remember { library.aiEditSelection.consume().orEmpty() }
        AiEditScreen(
            session = session,
            settings = settings,
            onBack = { nav.from(entry) { popBackStack() } },
            onOpenQueue = { nav.from(entry) { navigateTopLevel(TopLevelDestination.ACTIVITY) } },
            // Pushed on top of the review, so coming back finds the batch and its ticked boxes
            // exactly as they were.
            onOpenDoc = { docId -> nav.from(entry) { openDoc(docId, browse) } },
            onChat = { nav.from(entry) { navigateAi(Route.AiChat, restoreState = true) } },
            selection = selection,
        )
    }
    composable<Route.AiEditReview> { entry ->
        AiEditReviewScreen(
            session = session,
            jobId = entry.toRoute<Route.AiEditReview>().jobId,
            onBack = { nav.from(entry) { popBackStack() } },
            onOpenDoc = { docId -> nav.from(entry) { openDoc(docId, browse) } },
        )
    }
}
