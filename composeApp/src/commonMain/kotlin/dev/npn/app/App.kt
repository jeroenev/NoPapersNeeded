package dev.npn.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.npn.app.ui.common.LocaleProvider
import dev.npn.app.ui.common.TechnicalDetails
import dev.npn.app.ui.common.WideLayoutBreakpoint
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.detail.DocDetailScreen
import dev.npn.app.ui.detail.DocumentDraftHost
import dev.npn.app.ui.detail.LocalDocumentDrafts
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
import dev.npn.app.ui.queue.QueueScreen
import dev.npn.app.ui.rules.AutoMatchRulesScreen
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsScreen
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.trash.TrashScreen
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_open_library_title
import no_papers_needed.composeapp.generated.resources.common_open_saved_library_failed
import no_papers_needed.composeapp.generated.resources.common_opening_library
import no_papers_needed.composeapp.generated.resources.common_pick_folder_again
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.detail_unsaved_changes
import no_papers_needed.composeapp.generated.resources.library_settings
import no_papers_needed.composeapp.generated.resources.library_tags
import no_papers_needed.composeapp.generated.resources.library_trash
import no_papers_needed.composeapp.generated.resources.ux_library_draft_warning
import no_papers_needed.composeapp.generated.resources.ux_resume_draft
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(env: AppEnvironment) {
    LocaleProvider(env.settings) {
        NpnTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppRoot(env)
            }
        }
    }
}

@Composable
private fun AppRoot(env: AppEnvironment) {
    val session by env.session.collectAsStateWithLifecycle()
    val opening by env.opening.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var restoreError by remember { mutableStateOf<String?>(null) }
    var restoreAttempted by remember { mutableStateOf(false) }
    val defaultRestoreError = stringResource(Res.string.common_open_saved_library_failed)

    LaunchedEffect(Unit) {
        restoreError = null
        runCatching { env.openPersistedLibrary() }
            .onFailure { restoreError = it.message ?: defaultRestoreError }
        restoreAttempted = true
    }

    val current = session
    when {
        restoreError != null && current == null && !opening -> {
            RestoreErrorScreen(
                message = restoreError!!,
                onRetry = {
                    scope.launch {
                        restoreError = null
                        runCatching { env.openPersistedLibrary() }
                            .onFailure { restoreError = it.message ?: defaultRestoreError }
                    }
                },
                onRepickReturned = { location ->
                    restoreError = null
                    env.requestOpenLibrary(location)
                },
            )
        }

        opening || !restoreAttempted -> {
            LoadingScreen()
        }

        current == null -> {
            OnboardingScreen(env)
        }

        else -> {
            DocumentDraftHost(current, env.appScope) { MainNav(env = env, session = current) }
        }
    }
}

@Composable
private fun MainNav(
    env: AppEnvironment,
    session: dev.npn.core.app.LibrarySession,
) {
    val nav = rememberNavController()
    // Ordered doc list for prev/next-document paging in the detail screen; populated on open.
    val browse =
        remember {
            dev.npn.app.ui.detail
                .BrowseContext()
        }
    // Probe once whether the AI assistant is configured; gates the AI entries in rail and menus.
    val aiConfigured by produceState(initialValue = false, session) {
        value = session.aiSuggest.isConfiguredOrFalse()
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
    WithDraftResumeBanner(
        onOpen = { docId ->
            browse.setOrder(listOf(docId), null)
            nav.navigate(Route.DocDetail(docId)) {
                popUpTo(Route.Library)
                launchSingleTop = true
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val aiEditInSidebar = maxWidth > WideLayoutBreakpoint
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
                    aiConfigured = aiConfigured,
                    aiSelection = aiSelection,
                    libraryFilterHandoff = libraryFilterHandoff,
                    showAiEditTopBar = !aiEditInSidebar,
                )
            }
        }
    }
}

@Composable
private fun MainNavHost(
    nav: NavHostController,
    env: AppEnvironment,
    session: dev.npn.core.app.LibrarySession,
    browse: dev.npn.app.ui.detail.BrowseContext,
    aiConfigured: Boolean,
    aiSelection: dev.npn.app.ui.ai.AiEditSelection,
    libraryFilterHandoff: LibraryFilterHandoff,
    showAiEditTopBar: Boolean,
) {
    NavHost(navController = nav, startDestination = Route.Library) {
        composable<Route.Library> {
            LibraryScreen(
                session = session,
                env = env,
                aiConfigured = aiConfigured,
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
            TrashScreen(
                session = session,
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.TagManagement> {
            TagManagementScreen(
                session = session,
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.Queue> {
            QueueScreen(
                session = session,
                onBack = { nav.popBackStack() },
                onOpenEditRun = { jobId -> nav.navigate(Route.AiEditReview(jobId)) },
            )
        }
        settingsRoutes(nav, env, session, browse)
        aiRoutes(nav, session, env.settings, aiSelection, libraryFilterHandoff, showAiEditTopBar)
    }
}

/** Settings and the pages only reachable from it. */
private fun NavGraphBuilder.settingsRoutes(
    nav: NavHostController,
    env: AppEnvironment,
    session: dev.npn.core.app.LibrarySession,
    browse: dev.npn.app.ui.detail.BrowseContext,
) {
    composable<Route.Settings> { entry ->
        val draftStore = LocalDocumentDrafts.current
        // Holds the document id to resume, resolved when the dialog opens with the same predicate
        // the confirm button uses — so "Resume draft" always has somewhere to go, even when the
        // only unsaved work is an in-flight save with no dirty entry.
        var draftWarning by remember { mutableStateOf<String?>(null) }
        val changePicker =
            rememberDirectoryPickerLauncher { dir ->
                if (dir != null) {
                    env.requestOpenLibrary(
                        dev.npn.app.ui.common
                            .persistDirectoryAccess(dir),
                    )
                }
            }
        SettingsScreen(
            env = env,
            session = session,
            category = SettingsCategoryId.fromRouteKey(entry.toRoute<Route.Settings>().category),
            onOpenCategory = { id -> nav.navigate(Route.Settings(id.routeKey)) },
            onBack = { nav.popBackStack() },
            onChangeLibrary = {
                val pending = draftStore.state.entries.firstOrNull { it.value.dirty || it.value.saving }
                if (pending != null) {
                    draftWarning = pending.key
                } else {
                    changePicker.launch()
                }
            },
            onAutoMatchRules = { nav.navigate(Route.AutoMatchRules) },
        )
        draftWarning?.let { docId ->
            AlertDialog(
                onDismissRequest = { draftWarning = null },
                title = { Text(stringResource(Res.string.detail_unsaved_changes)) },
                text = { Text(stringResource(Res.string.ux_library_draft_warning)) },
                confirmButton = {
                    TextButton(onClick = {
                        draftWarning = null
                        // Mirror the resume banner: land back on Library with the detail on top,
                        // so Back returns to the library rather than the settings stack.
                        browse.setOrder(listOf(docId), null)
                        nav.navigate(Route.DocDetail(docId)) {
                            popUpTo(Route.Library)
                            launchSingleTop = true
                        }
                    }) { Text(stringResource(Res.string.ux_resume_draft)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { draftWarning = null },
                    ) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }
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
    LaunchedEffect(windowTitle) {
        if (windowTitle != null) DesktopBridge.windowTitle.value = windowTitle
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
            selection = selection,
        )
    }
    composable<Route.AiEditReview> { entry ->
        AiEditReviewScreen(
            session = session,
            jobId = entry.toRoute<Route.AiEditReview>().jobId,
            onBack = { nav.popBackStack() },
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

@Composable
private fun RestoreErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onRepickReturned: (String) -> Unit,
) {
    val genericMessage = stringResource(Res.string.common_open_saved_library_failed)
    val picker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir != null) {
                onRepickReturned(
                    dev.npn.app.ui.common
                        .persistDirectoryAccess(dir),
                )
            }
        }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(stringResource(Res.string.common_open_library_title), style = MaterialTheme.typography.titleMedium)
            Text(
                genericMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (message != genericMessage) {
                TechnicalDetails(message)
            }
            Button(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
            OutlinedButton(onClick = { picker.launch() }) {
                Text(stringResource(Res.string.common_pick_folder_again))
            }
        }
    }
}
