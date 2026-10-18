package dev.npn.app.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.npn.app.ui.ai.AiChatScreen
import dev.npn.app.ui.ai.AiEditReviewScreen
import dev.npn.app.ui.ai.AiEditScreen
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.PublishWindowTitle
import dev.npn.app.ui.detail.BrowseContext
import dev.npn.app.ui.detail.DocDetailScreen
import dev.npn.app.ui.detail.WithDraftResumeBanner
import dev.npn.app.ui.library.LibraryScreen
import dev.npn.app.ui.queue.QueueScreen
import dev.npn.app.ui.rules.AutoMatchRulesScreen
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsScreen
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.trash.TrashScreen
import dev.npn.app.ui.update.WithUpdateBanner
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_rules_title
import org.jetbrains.compose.resources.stringResource

/**
 * The open library's screens: the navigation shell around the [library]'s nav graph, with the
 * banners that sit above every screen. [queue] is the shell's badge; [snackbar] is the shell's own,
 * for what is reported outside any one screen.
 */
@Composable
internal fun MainNav(
    settings: SettingsRepository,
    session: LibrarySession,
    /** The library's navigation, the browse order the detail screen pages through, and its hand-offs. */
    library: LibraryUi,
    aiConfigured: Boolean,
    queue: QueueStatus,
    snackbar: SnackbarHostState,
    onOpenLibrary: (location: String) -> Unit,
) {
    val nav = library.nav
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val topLevel = destination.topLevelDestination()
    val ai = destination.aiDestination()

    PublishWindowTitle(windowTitle(destination, topLevel, ai))
    WithUpdateBanner(settings, snackbar) {
        WithDraftResumeBanner(onOpen = { docId -> nav.resumeDraft(docId, library.browse) }) {
            // The rail carries AI edit on a wide window, so the chat's top bar offers it only on a
            // compact one.
            val aiEditInSidebar = LocalWindowWidthClass.current.isWide
            NavigationShell(
                current = topLevel,
                aiCurrent = ai,
                aiConfigured = aiConfigured,
                queue = queue,
                onNavigate = { dest -> nav.navigateTopLevel(dest) },
                onAiChat = { nav.navigateAi(Route.AiChat, restoreState = true) },
                onAiEdit = { nav.openAiEdit(library) },
            ) {
                LibraryViewModelHost(library) {
                    MainNavHost(
                        settings = settings,
                        session = session,
                        library = library,
                        showAiEditTopBar = !aiEditInSidebar,
                        onOpenLibrary = onOpenLibrary,
                    )
                }
            }
        }
    }
}

/** The shell destination [this] belongs to; null hides the rail/bottom bar. Library before the first entry. */
private fun NavDestination?.topLevelDestination(): TopLevelDestination? =
    when {
        this == null -> TopLevelDestination.LIBRARY
        hasRoute<Route.Library>() -> TopLevelDestination.LIBRARY
        hasRoute<Route.TagManagement>() -> TopLevelDestination.TAGS
        hasRoute<Route.Trash>() -> TopLevelDestination.TRASH
        hasRoute<Route.Queue>() -> TopLevelDestination.QUEUE
        hasRoute<Route.Settings>() -> TopLevelDestination.SETTINGS
        else -> null
    }

/** The AI screen [this] is, if any; keeps the rail visible and its AI item selected on wide layouts. */
private fun NavDestination?.aiDestination(): AiDestination? =
    when {
        this == null -> null
        hasRoute<Route.AiChat>() -> AiDestination.CHAT
        hasRoute<Route.AiEdit>() -> AiDestination.EDIT
        else -> null
    }

/**
 * The native window title for [destination]: the label the shell gives it ([topLevel], [ai]), the
 * app's name on Library, and null on a document, whose screen publishes the document's own title once
 * it has loaded.
 */
@Composable
private fun windowTitle(
    destination: NavDestination?,
    topLevel: TopLevelDestination?,
    ai: AiDestination?,
): String? {
    val label =
        when {
            destination?.hasRoute<Route.DocDetail>() == true -> return null

            destination?.hasRoute<Route.AutoMatchRules>() == true -> Res.string.common_rules_title

            // The review of a background run is AI edit's, opened from the queue.
            destination?.hasRoute<Route.AiEditReview>() == true -> AiDestination.EDIT.label

            topLevel == TopLevelDestination.LIBRARY -> Res.string.common_app_name

            else -> topLevel?.label ?: ai?.label ?: Res.string.common_app_name
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
 * own (from the queue, the assistant, an AI edit) gets an order of just itself, so the detail never
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

/** Open AI edit on [docIds], handed over through [library]; none opens it to pick its own scope. */
private fun NavHostController.openAiEdit(
    library: LibraryUi,
    docIds: List<String> = emptyList(),
) {
    library.aiEditSelection.set(docIds)
    navigateAi(Route.AiEdit)
}

@Composable
private fun MainNavHost(
    settings: SettingsRepository,
    session: LibrarySession,
    library: LibraryUi,
    showAiEditTopBar: Boolean,
    onOpenLibrary: (location: String) -> Unit,
) {
    val nav = library.nav
    val browse = library.browse
    NavHost(navController = nav, startDestination = Route.Library) {
        composable<Route.Library> {
            LibraryScreen(
                session = session,
                settings = settings,
                onOpenDoc = { docId, order, query -> nav.openDoc(docId, browse, order, query) },
                onTrash = { nav.navigateTopLevel(TopLevelDestination.TRASH) },
                onOpenQueue = { nav.navigateTopLevel(TopLevelDestination.QUEUE) },
                onAiEdit = { docIds -> nav.openAiEdit(library, docIds) },
                filterHandoff = library.libraryFilter,
            )
        }
        composable<Route.DocDetail> { entry ->
            val route = entry.toRoute<Route.DocDetail>()
            DocDetailScreen(
                docId = route.docId,
                session = session,
                settings = settings,
                browse = browse,
                onBack = { if (nav.currentBackStackEntry == entry) nav.popBackStack() },
                onNavigateToDoc = { targetId ->
                    if (nav.currentBackStackEntry != entry) return@DocDetailScreen
                    // Replace the current detail route so the back stack stays "library -> detail".
                    nav.navigate(Route.DocDetail(targetId)) {
                        popUpTo(Route.DocDetail(route.docId)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Route.Trash> {
            TrashScreen(session = session)
        }
        composable<Route.TagManagement> {
            TagManagementScreen(session = session)
        }
        composable<Route.Queue> {
            QueueScreen(
                session = session,
                // A queued document is a real document; open it on its own, the way the resume banner
                // does, so Back from detail returns to the queue.
                onOpenDoc = { docId -> nav.openDoc(docId, browse) },
                onOpenEditRun = { jobId -> nav.navigate(Route.AiEditReview(jobId)) },
            )
        }
        settingsRoutes(nav, settings, session, browse, onOpenLibrary)
        aiRoutes(library, session, settings, showAiEditTopBar)
    }
}

/** Settings and the pages only reachable from it. */
private fun NavGraphBuilder.settingsRoutes(
    nav: NavHostController,
    settings: SettingsRepository,
    session: LibrarySession,
    browse: BrowseContext,
    onOpenLibrary: (location: String) -> Unit,
) {
    composable<Route.Settings> { entry ->
        SettingsScreen(
            settings = settings,
            session = session,
            category = SettingsCategoryId.fromRouteKey(entry.toRoute<Route.Settings>().category),
            onOpenCategory = { id -> nav.navigate(Route.Settings(id.routeKey)) },
            onBack = { nav.popBackStack() },
            onOpenLibrary = onOpenLibrary,
            onResumeDraft = { docId -> nav.resumeDraft(docId, browse) },
            onAutoMatchRules = { nav.navigate(Route.AutoMatchRules) },
        )
    }
    composable<Route.AutoMatchRules> {
        AutoMatchRulesScreen(
            session = session,
            onBack = { nav.popBackStack() },
        )
    }
}

/** AI assistant + AI tagging destinations (both reached from the library drawer). */
private fun NavGraphBuilder.aiRoutes(
    library: LibraryUi,
    session: LibrarySession,
    settings: SettingsRepository,
    showAiEditTopBar: Boolean,
) {
    val nav = library.nav
    val browse = library.browse
    composable<Route.AiChat> {
        AiChatScreen(
            session = session,
            settings = settings,
            onBack = { nav.popBackStack() },
            onOpenDoc = { docId -> nav.openDoc(docId, browse) },
            onShowInLibrary = { docIds ->
                library.libraryFilter.set(LibraryFilter(docIds = docIds))
                nav.navigateTopLevel(TopLevelDestination.LIBRARY)
            },
            onAiEditDocs = { docIds -> nav.openAiEdit(library, docIds) },
            onAiEdit =
                if (showAiEditTopBar) {
                    { nav.openAiEdit(library) }
                } else {
                    null
                },
        )
    }
    composable<Route.AiEdit> {
        // Consumed once per navigation; remembered so recomposition keeps the same hand-off.
        val selection = remember { library.aiEditSelection.consume().orEmpty() }
        AiEditScreen(
            session = session,
            settings = settings,
            onBack = { nav.popBackStack() },
            onOpenQueue = { nav.navigateTopLevel(TopLevelDestination.QUEUE) },
            // Pushed on top of the review, so coming back finds the batch and its ticked boxes
            // exactly as they were.
            onOpenDoc = { docId -> nav.openDoc(docId, browse) },
            selection = selection,
        )
    }
    composable<Route.AiEditReview> { entry ->
        AiEditReviewScreen(
            session = session,
            jobId = entry.toRoute<Route.AiEditReview>().jobId,
            onBack = { nav.popBackStack() },
            onOpenDoc = { docId -> nav.openDoc(docId, browse) },
        )
    }
}
