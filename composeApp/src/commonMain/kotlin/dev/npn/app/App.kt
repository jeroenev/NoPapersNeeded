package dev.npn.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.ai.AiChatScreen
import dev.npn.app.ui.ai.AiEditReviewScreen
import dev.npn.app.ui.ai.AiEditScreen
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.LocaleProvider
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.testTagsAsResourceIds
import dev.npn.app.ui.detail.DocDetailScreen
import dev.npn.app.ui.detail.DocumentDraftHost
import dev.npn.app.ui.detail.WithDraftResumeBanner
import dev.npn.app.ui.library.LibraryFilterHandoff
import dev.npn.app.ui.library.LibraryScreen
import dev.npn.app.ui.nav.AiDestination
import dev.npn.app.ui.nav.NavigationShell
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.app.ui.nav.Route
import dev.npn.app.ui.nav.TopLevelDestination
import dev.npn.app.ui.nav.queueStatus
import dev.npn.app.ui.onboarding.OnboardingScreen
import dev.npn.app.ui.onboarding.OpenFailureMessage
import dev.npn.app.ui.onboarding.RestoreFailedScreen
import dev.npn.app.ui.queue.QueueScreen
import dev.npn.app.ui.rules.AutoMatchRulesScreen
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsScreen
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.trash.TrashScreen
import dev.npn.app.ui.update.WithUpdateBanner
import kotlinx.coroutines.flow.receiveAsFlow
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_ok
import no_papers_needed.composeapp.generated.resources.common_opening_library
import no_papers_needed.composeapp.generated.resources.library_settings
import no_papers_needed.composeapp.generated.resources.library_tags
import no_papers_needed.composeapp.generated.resources.library_trash
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(env: AppEnvironment) {
    LocaleProvider(env.settings) {
        NpnTheme {
            ProvideWindowWidthClass {
                Surface(Modifier.fillMaxSize().testTagsAsResourceIds(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(env)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    env: AppEnvironment,
    modifier: Modifier = Modifier,
    vm: AppViewModel = viewModel { AppViewModel(env) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val useSaf by vm.useSafStorage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)
    // Files dropped on the desktop window import into whichever library is open, from any screen.
    LaunchedEffect(vm) { DesktopBridge.droppedFiles.receiveAsFlow().collect { vm.importFiles(it) } }
    // Without a library there are no screens to title the window; the app's name, in its language.
    if (state !is AppState.Open) PublishWindowTitle(stringResource(Res.string.common_app_name))

    Box(modifier.fillMaxSize()) {
        when (val current = state) {
            AppState.Restoring, AppState.Opening -> {
                LoadingScreen()
            }

            is AppState.NoLibrary -> {
                OnboardingScreen(
                    failure = current.failure,
                    useSaf = useSaf,
                    onUseSafChange = vm::setUseSafStorage,
                    onOpen = vm::open,
                    onCreate = vm::createLibrary,
                    onUseRecommendedFolder = vm::useRecommended,
                )
            }

            is AppState.RestoreFailed -> {
                RestoreFailedScreen(
                    failure = current.failure,
                    useSaf = useSaf,
                    onUseSafChange = vm::setUseSafStorage,
                    onRetry = vm::retry,
                    onOpen = vm::open,
                )
            }

            is AppState.Open -> {
                val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()
                DocumentDraftHost(current.session, env.appScope) {
                    MainNav(env = env, session = current.session, aiConfigured = aiConfigured, onOpenLibrary = vm::open)
                }
                // A switch to another library that failed lands back in this one; say why.
                current.failure?.let { OpenFailedDialog(it, onDismiss = vm::dismissFailure) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun OpenFailedDialog(
    failure: OpenFailure,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.common_could_not_open_library)) },
        text = { OpenFailureMessage(failure) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
        },
    )
}

@Composable
private fun MainNav(
    env: AppEnvironment,
    session: dev.npn.core.app.LibrarySession,
    aiConfigured: Boolean,
    onOpenLibrary: (location: String) -> Unit,
) {
    val nav = rememberNavController()
    // Ordered doc list for prev/next-document paging in the detail screen; populated on open.
    val browse =
        remember {
            dev.npn.app.ui.detail
                .BrowseContext()
        }
    // Outstanding background work, for the shell's queue badge. Read here rather than in a screen
    // because the badge is the only standing sign that the app is still busy — or, once a
    // background AI edit finishes, that it is waiting on the user — and on compact layouts the
    // queue itself sits behind the bar's "More" item.
    val queue by remember(session) { session.work.queueStatus() }
        .collectAsStateWithLifecycle(QueueStatus.None)

    // Documents handed from the library's selection mode to the AI-edit page (see AiEditSelection).
    val aiSelection =
        remember {
            dev.npn.app.ui.ai
                .AiEditSelection()
        }
    val libraryFilterHandoff = remember { LibraryFilterHandoff() }

    DriveWindowTitle(nav)
    WithUpdateBanner(env.settings) {
        WithDraftResumeBanner(onOpen = { docId -> nav.resumeDraft(docId, browse) }) {
            // The rail carries AI edit on a wide window, so the chat's top bar offers it only on a
            // compact one.
            val aiEditInSidebar = LocalWindowWidthClass.current.isWide
            NavigationShell(
                current = nav.currentTopLevelDestination(),
                aiCurrent = nav.currentAiDestination(),
                aiConfigured = aiConfigured,
                queue = queue,
                onNavigate = { dest -> nav.navigateTopLevel(dest) },
                onAiChat = { nav.navigateAi(Route.AiChat, restoreState = true) },
                onAiEdit = {
                    aiSelection.set(emptyList())
                    nav.navigateAi(Route.AiEdit)
                },
            ) {
                MainNavHost(
                    nav = nav,
                    env = env,
                    session = session,
                    browse = browse,
                    aiSelection = aiSelection,
                    libraryFilterHandoff = libraryFilterHandoff,
                    showAiEditTopBar = !aiEditInSidebar,
                    onOpenLibrary = onOpenLibrary,
                )
            }
        }
    }
}

/**
 * Open the document a draft was left on, the way the resume banner does: back on Library with the
 * detail on top, so Back returns to the library rather than to wherever the user was.
 */
private fun NavHostController.resumeDraft(
    docId: String,
    browse: dev.npn.app.ui.detail.BrowseContext,
) {
    browse.setOrder(listOf(docId), null)
    navigate(Route.DocDetail(docId)) {
        popUpTo(Route.Library)
        launchSingleTop = true
    }
}

@Composable
private fun MainNavHost(
    nav: NavHostController,
    env: AppEnvironment,
    session: dev.npn.core.app.LibrarySession,
    browse: dev.npn.app.ui.detail.BrowseContext,
    aiSelection: dev.npn.app.ui.ai.AiEditSelection,
    libraryFilterHandoff: LibraryFilterHandoff,
    showAiEditTopBar: Boolean,
    onOpenLibrary: (location: String) -> Unit,
) {
    NavHost(navController = nav, startDestination = Route.Library) {
        composable<Route.Library> {
            LibraryScreen(
                session = session,
                env = env,
                onOpenDoc = { docId, order, query ->
                    browse.setOrder(order, query)
                    nav.navigate(Route.DocDetail(docId))
                },
                onTrash = { nav.navigate(Route.Trash) },
                onOpenQueue = { nav.navigate(Route.Queue) },
                onAiEdit = { docIds ->
                    aiSelection.set(docIds)
                    nav.navigateAi(Route.AiEdit)
                },
                filterHandoff = libraryFilterHandoff,
            )
        }
        composable<Route.DocDetail> { entry ->
            val route = entry.toRoute<Route.DocDetail>()
            DocDetailScreen(
                docId = route.docId,
                session = session,
                settings = env.settings,
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
                onOpenDoc = { docId ->
                    // A queued document is a real document; open it on its own, the way the resume
                    // banner does, so Back from detail returns to the queue.
                    browse.setOrder(listOf(docId), null)
                    nav.navigate(Route.DocDetail(docId))
                },
                onOpenEditRun = { jobId -> nav.navigate(Route.AiEditReview(jobId)) },
            )
        }
        settingsRoutes(nav, env, session, browse, onOpenLibrary)
        aiRoutes(nav, session, env.settings, aiSelection, libraryFilterHandoff, showAiEditTopBar)
    }
}

/** Settings and the pages only reachable from it. */
private fun NavGraphBuilder.settingsRoutes(
    nav: NavHostController,
    env: AppEnvironment,
    session: dev.npn.core.app.LibrarySession,
    browse: dev.npn.app.ui.detail.BrowseContext,
    onOpenLibrary: (location: String) -> Unit,
) {
    composable<Route.Settings> { entry ->
        SettingsScreen(
            env = env,
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

/** Maps the current back stack entry to a shell destination; null hides the rail/bottom bar. */
@Composable
private fun NavHostController.currentTopLevelDestination(): TopLevelDestination? {
    val entry by currentBackStackEntryAsState()
    val destination = entry?.destination ?: return TopLevelDestination.LIBRARY
    return when {
        destination.hasRoute<Route.Library>() -> TopLevelDestination.LIBRARY
        destination.hasRoute<Route.TagManagement>() -> TopLevelDestination.TAGS
        destination.hasRoute<Route.Trash>() -> TopLevelDestination.TRASH
        destination.hasRoute<Route.Queue>() -> TopLevelDestination.QUEUE
        destination.hasRoute<Route.Settings>() -> TopLevelDestination.SETTINGS
        else -> null
    }
}

/** The open AI screen, if any; keeps the rail visible and its AI item selected on wide layouts. */
@Composable
private fun NavHostController.currentAiDestination(): AiDestination? {
    val entry by currentBackStackEntryAsState()
    val destination = entry?.destination ?: return null
    return when {
        destination.hasRoute<Route.AiChat>() -> AiDestination.CHAT
        destination.hasRoute<Route.AiEdit>() -> AiDestination.EDIT
        else -> null
    }
}

private fun NavHostController.navigateTopLevel(dest: TopLevelDestination) {
    val route: Route =
        when (dest) {
            TopLevelDestination.LIBRARY -> Route.Library
            TopLevelDestination.TAGS -> Route.TagManagement
            TopLevelDestination.TRASH -> Route.Trash
            TopLevelDestination.QUEUE -> Route.Queue
            TopLevelDestination.SETTINGS -> Route.Settings()
        }
    navigate(route) {
        // Keep a single branch: switching destinations never stacks top-level screens on
        // each other, and each destination's state survives the round trip.
        popUpTo(Route.Library) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** AI screens sit one level above Library, never stacked on other pushed screens or each other. */
private fun NavHostController.navigateAi(
    route: Route,
    restoreState: Boolean = false,
) {
    navigate(route) {
        popUpTo(Route.Library) { saveState = true }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}

/**
 * Drive the native window title from the current route. The document detail route is skipped: that
 * screen publishes the real document title to the same flow once its document has loaded.
 */
@Composable
private fun DriveWindowTitle(nav: NavHostController) {
    val currentEntry by nav.currentBackStackEntryAsState()
    val destination = currentEntry?.destination
    val windowTitle: String? =
        when {
            destination?.hasRoute<Route.DocDetail>() == true -> {
                null
            }

            destination?.hasRoute<Route.Trash>() == true -> {
                stringResource(Res.string.library_trash)
            }

            destination?.hasRoute<Route.TagManagement>() == true -> {
                stringResource(Res.string.library_tags)
            }

            destination?.hasRoute<Route.Settings>() == true -> {
                stringResource(Res.string.library_settings)
            }

            else -> {
                stringResource(Res.string.common_app_name)
            }
        }
    PublishWindowTitle(windowTitle)
}

/** Make [title] the native window title (desktop); null leaves the title to a screen below. */
@Composable
private fun PublishWindowTitle(title: String?) {
    LaunchedEffect(title) {
        if (title != null) DesktopBridge.windowTitle.value = title
    }
}

/** AI assistant + AI tagging destinations (both reached from the library drawer). */
private fun NavGraphBuilder.aiRoutes(
    nav: NavHostController,
    session: dev.npn.core.app.LibrarySession,
    settings: dev.npn.core.app.SettingsRepository,
    aiSelection: dev.npn.app.ui.ai.AiEditSelection,
    libraryFilterHandoff: LibraryFilterHandoff,
    showAiEditTopBar: Boolean,
) {
    composable<Route.AiChat> {
        AiChatScreen(
            session = session,
            settings = settings,
            onBack = { nav.popBackStack() },
            onOpenDoc = { docId -> nav.navigate(Route.DocDetail(docId)) },
            onOpenFiltered = { docIds ->
                libraryFilterHandoff.set(
                    dev.npn.core.app
                        .LibraryFilter(docIds = docIds),
                )
                nav.navigateTopLevel(TopLevelDestination.LIBRARY)
            },
            onAiEditDocs = { docIds ->
                aiSelection.set(docIds)
                nav.navigateAi(Route.AiEdit)
            },
            showAiEdit = showAiEditTopBar,
            onAiEdit = {
                aiSelection.set(emptyList())
                nav.navigateAi(Route.AiEdit)
            },
        )
    }
    composable<Route.AiEdit> {
        // Consumed once per navigation; remembered so recomposition keeps the same hand-off.
        val selection = remember { aiSelection.consume() }
        AiEditScreen(
            session = session,
            settings = settings,
            onBack = { nav.popBackStack() },
            onOpenQueue = { nav.navigateTopLevel(TopLevelDestination.QUEUE) },
            // Pushed on top of the review, so coming back finds the batch and its ticked boxes
            // exactly as they were.
            onOpenDoc = { docId -> nav.navigate(Route.DocDetail(docId)) },
            selection = selection,
        )
    }
    composable<Route.AiEditReview> { entry ->
        AiEditReviewScreen(
            session = session,
            jobId = entry.toRoute<Route.AiEditReview>().jobId,
            onBack = { nav.popBackStack() },
            onOpenDoc = { docId -> nav.navigate(Route.DocDetail(docId)) },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(Res.string.common_opening_library), Modifier.padding(top = 16.dp))
        }
    }
}
