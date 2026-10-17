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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.ai.AiChatScreen
import dev.npn.app.ui.ai.AiEditReviewScreen
import dev.npn.app.ui.ai.AiEditScreen
import dev.npn.app.ui.ai.AiEditSelection
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.LocaleProvider
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.common.PublishWindowTitle
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.testTagsAsResourceIds
import dev.npn.app.ui.detail.BrowseContext
import dev.npn.app.ui.detail.DocDetailScreen
import dev.npn.app.ui.detail.DocumentDraftHost
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.WithDraftResumeBanner
import dev.npn.app.ui.detail.rememberDocumentDrafts
import dev.npn.app.ui.library.LibraryFilterHandoff
import dev.npn.app.ui.library.LibraryScreen
import dev.npn.app.ui.nav.AiDestination
import dev.npn.app.ui.nav.NavigationShell
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.app.ui.nav.Route
import dev.npn.app.ui.nav.TopLevelDestination
import dev.npn.app.ui.nav.navigateAi
import dev.npn.app.ui.nav.navigateTopLevel
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
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
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
import kotlin.random.Random

@Composable
@NonRestartableComposable
fun App(env: AppEnvironment) {
    AppRoot(env)
}

/**
 * What the open library's UI keeps when the app's language changes: its unsaved [drafts], where its
 * navigation stands ([nav]), its screens' viewmodels ([viewModels]), and the order the detail
 * screen walks ([browse]). All of it belongs to one session: another library gets its own.
 */
@Stable
internal class LibraryUi(
    val drafts: DocumentDrafts,
    val nav: NavHostController,
    val viewModels: ViewModelStoreOwner,
) {
    val browse = BrowseContext()
}

/**
 * The viewmodel stores of the open library's screens, one per session. Held in the window's store
 * (the activity's on Android), so an activity recreated for a configuration change finds its
 * library's screens as they were. [keepOnly] clears every other session's store, so nothing a
 * closed library's screens started runs on against it.
 */
internal class LibraryViewModelStores : ViewModel() {
    private val owners = HashMap<LibrarySession, ViewModelStoreOwner>()

    fun of(session: LibrarySession): ViewModelStoreOwner =
        owners.getOrPut(session) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    fun keepOnly(session: LibrarySession?) {
        for (gone in owners.keys.filter { it != session }) owners.remove(gone)?.viewModelStore?.clear()
    }

    override fun onCleared() = keepOnly(null)
}

/**
 * [content] in the app's language ([LocaleProvider]), handed the open library's [LibraryUi], or
 * null while no library is open. A change of language rebuilds everything below [LocaleProvider],
 * so what must outlive it is remembered here, above it, for as long as [session] is open. Once the
 * UI of a session that left is gone, its screens' viewmodels are cleared.
 */
@Composable
internal fun LocalizedApp(
    settings: SettingsRepository,
    session: LibrarySession?,
    scope: CoroutineScope,
    stores: LibraryViewModelStores = viewModel { LibraryViewModelStores() },
    content: @Composable (LibraryUi?) -> Unit,
) {
    val library = session?.let { rememberLibraryUi(it, scope, stores.of(it)) }
    // Side effects run once the composition has let go of what it no longer shows.
    SideEffect { stores.keepOnly(session) }
    LocaleProvider(settings) { content(library) }
}

@Composable
private fun rememberLibraryUi(
    session: LibrarySession,
    scope: CoroutineScope,
    viewModels: ViewModelStoreOwner,
): LibraryUi {
    val drafts = rememberDocumentDrafts(session, scope)
    // A controller of the session's own, as its back stack holds the session's viewmodel stores: a
    // new one for another session, and the saved one again for the same session in a recreated
    // activity or a restored process. Saved, so the restore finds the key it was saved under.
    val navKey = rememberSaveable(session) { Random.nextLong() }
    val nav = key(navKey) { rememberNavController() }
    return remember(drafts, nav, viewModels) { LibraryUi(drafts, nav, viewModels) }
}

/** [content], with [library]'s screens' viewmodels kept in its store ([LibraryUi.viewModels]). */
@Composable
internal fun LibraryViewModelHost(
    library: LibraryUi,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalViewModelStoreOwner provides library.viewModels, content = content)
}

@Composable
private fun AppRoot(
    env: AppEnvironment,
    modifier: Modifier = Modifier,
    vm: AppViewModel = viewModel { AppViewModel(env) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val useSaf by vm.useSafStorage.collectAsStateWithLifecycle()
    LocalizedApp(env.settings, (state as? AppState.Open)?.session, env.appScope) { library ->
        AppSurface {
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
                        // Never null here: LocalizedApp remembers it for the session this state opened.
                        // Keyed, so nothing remembered for one library's screens carries over to
                        // another's when the session changes with no screen in between.
                        if (library != null) {
                            key(library) {
                                DocumentDraftHost(library.drafts) {
                                    MainNav(
                                        settings = env.settings,
                                        session = current.session,
                                        library = library,
                                        aiConfigured = aiConfigured,
                                        snackbar = snackbar,
                                        onOpenLibrary = vm::open,
                                    )
                                }
                            }
                        }
                        // A switch to another library that failed lands back in this one; say why.
                        current.failure?.let { OpenFailedDialog(it, onDismiss = vm::dismissFailure) }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
}

/** The app's theme, window-width class and background around [content]. */
@Composable
private fun AppSurface(content: @Composable () -> Unit) {
    NpnTheme {
        ProvideWindowWidthClass {
            Surface(
                Modifier.fillMaxSize().testTagsAsResourceIds(),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
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
    settings: SettingsRepository,
    session: LibrarySession,
    /** The library's navigation and the browse order the detail screen pages through. */
    library: LibraryUi,
    aiConfigured: Boolean,
    /** The shell's own snackbar, for what is reported outside any one screen. */
    snackbar: SnackbarHostState,
    onOpenLibrary: (location: String) -> Unit,
) {
    val nav = library.nav
    // Ordered doc list for prev/next-document paging in the detail screen; populated on open.
    val browse = library.browse
    // Outstanding background work, for the shell's queue badge. Read here rather than in a screen
    // because the badge is the only standing sign that the app is still busy — or, once a
    // background AI edit finishes, that it is waiting on the user — and on compact layouts the
    // queue itself sits behind the bar's "More" item.
    val queue by remember(session) { session.work.queueStatus() }
        .collectAsStateWithLifecycle(QueueStatus.None)

    // Documents handed from the library's selection mode to the AI-edit page (see AiEditSelection).
    val aiSelection =
        remember {
            AiEditSelection()
        }
    val libraryFilterHandoff = remember { LibraryFilterHandoff() }

    DriveWindowTitle(nav)
    WithUpdateBanner(settings, snackbar) {
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
                onAiEdit = { nav.openAiEdit(aiSelection) },
            ) {
                LibraryViewModelHost(library) {
                    MainNavHost(
                        nav = nav,
                        settings = settings,
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

@Composable
private fun MainNavHost(
    nav: NavHostController,
    settings: SettingsRepository,
    session: LibrarySession,
    browse: BrowseContext,
    aiSelection: AiEditSelection,
    libraryFilterHandoff: LibraryFilterHandoff,
    showAiEditTopBar: Boolean,
    onOpenLibrary: (location: String) -> Unit,
) {
    NavHost(navController = nav, startDestination = Route.Library) {
        composable<Route.Library> {
            LibraryScreen(
                session = session,
                settings = settings,
                onOpenDoc = { docId, order, query -> nav.openDoc(docId, browse, order, query) },
                onTrash = { nav.navigateTopLevel(TopLevelDestination.TRASH) },
                onOpenQueue = { nav.navigateTopLevel(TopLevelDestination.QUEUE) },
                onAiEdit = { docIds -> nav.openAiEdit(aiSelection, docIds) },
                filterHandoff = libraryFilterHandoff,
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
        aiRoutes(nav, session, settings, browse, aiSelection, libraryFilterHandoff, showAiEditTopBar)
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

/** Open AI edit on [docIds], handed over through [selection]; none opens it to pick its own scope. */
private fun NavHostController.openAiEdit(
    selection: AiEditSelection,
    docIds: List<String> = emptyList(),
) {
    selection.set(docIds)
    navigateAi(Route.AiEdit)
}

/** AI assistant + AI tagging destinations (both reached from the library drawer). */
private fun NavGraphBuilder.aiRoutes(
    nav: NavHostController,
    session: LibrarySession,
    settings: SettingsRepository,
    browse: BrowseContext,
    aiSelection: AiEditSelection,
    libraryFilterHandoff: LibraryFilterHandoff,
    showAiEditTopBar: Boolean,
) {
    composable<Route.AiChat> {
        AiChatScreen(
            session = session,
            settings = settings,
            onBack = { nav.popBackStack() },
            onOpenDoc = { docId -> nav.openDoc(docId, browse) },
            onOpenFiltered = { docIds ->
                libraryFilterHandoff.set(
                    LibraryFilter(docIds = docIds),
                )
                nav.navigateTopLevel(TopLevelDestination.LIBRARY)
            },
            onAiEditDocs = { docIds -> nav.openAiEdit(aiSelection, docIds) },
            onAiEdit =
                if (showAiEditTopBar) {
                    { nav.openAiEdit(aiSelection) }
                } else {
                    null
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

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(Res.string.common_opening_library), Modifier.padding(top = 16.dp))
        }
    }
}
