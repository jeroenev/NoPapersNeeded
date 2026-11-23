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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.Handoff
import dev.npn.app.ui.common.KeyedViewModelStores
import dev.npn.app.ui.common.LocaleProvider
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.common.PublishWindowTitle
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.app.ui.common.testTagsAsResourceIds
import dev.npn.app.ui.detail.BrowseContext
import dev.npn.app.ui.detail.DocumentDraftHost
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.library.DropOverlay
import dev.npn.app.ui.nav.MainNav
import dev.npn.app.ui.nav.Route
import dev.npn.app.ui.nav.TopLevelDestination
import dev.npn.app.ui.nav.WriteBlockActions
import dev.npn.app.ui.nav.navigateTopLevel
import dev.npn.app.ui.onboarding.FirstRunExit
import dev.npn.app.ui.onboarding.FirstRunHost
import dev.npn.app.ui.onboarding.FirstRunPath
import dev.npn.app.ui.onboarding.FirstRunScreen
import dev.npn.app.ui.onboarding.OnboardingScreen
import dev.npn.app.ui.onboarding.OpenFailureMessage
import dev.npn.app.ui.onboarding.RestoreFailedScreen
import dev.npn.app.ui.onboarding.rememberFirstRunHost
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.whatsnew.WhatsNewDialog
import dev.npn.app.ui.whatsnew.WhatsNewGateViewModel
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.fs.ExternalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_ok
import no_papers_needed.composeapp.generated.resources.common_opening_library
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

@Composable
@NonRestartableComposable
fun App(env: AppEnvironment) {
    AppRoot(env)
}

/**
 * The open library's UI as its screens use it: where its navigation stands ([nav]) and what its
 * session keeps for them ([LibrarySessionUi]): its unsaved [drafts], its screens' viewmodels
 * ([viewModels]) and each document's ([documents]), and what one screen hands another as it
 * navigates there, such as the order the detail screen walks. All of it belongs to one session:
 * another library gets its own.
 */
@Stable
internal class LibraryUi(
    held: LibrarySessionUi,
    val drafts: DocumentDrafts,
    val nav: NavHostController,
    /** The app's scope, for what a screen leaves to finish after it is gone. */
    val outliving: CoroutineScope,
) {
    val viewModels: ViewModelStoreOwner = held
    val documents = held.documents
    val browse = held.handover.browse
    val libraryFilter = held.handover.libraryFilter
    val aiEditSelection = held.handover.aiEditSelection

    /** Show the library narrowed to [docIds], from whatever screen is showing (a drop's "Show"). */
    fun showDocuments(docIds: List<String>) {
        libraryFilter.set(LibraryFilter(docIds = docIds))
        nav.navigateTopLevel(TopLevelDestination.LIBRARY)
    }
}

/**
 * What the open library's screens hand each other as they navigate. Kept for the session
 * ([LibrarySessionUi]), so a window recreated for a configuration change still has it, and dropped
 * with it. A process restore starts without it, as a screen opened afresh would.
 */
internal class LibraryHandover {
    /** The order the detail screen walks, and the search it highlights. */
    val browse = BrowseContext()

    /** A filter for the library to apply when it shows (the assistant's "show in library"). */
    val libraryFilter = Handoff<LibraryFilter>()

    /** Documents handed from the library's selection mode (or the assistant) to the AI-edit page. */
    val aiEditSelection = Handoff<List<String>>()
}

/**
 * What the open library's UI keeps for as long as its session is open, through a recreated window
 * and a change of language alike: its screens' viewmodels (this store), each document's viewmodel
 * ([documents], held by the screens showing the document, so the library's side panel and the detail
 * screen share one), the unsaved drafts ([drafts]) and what its screens hand each other ([handover]).
 */
internal class LibrarySessionUi : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val documents = KeyedViewModelStores<String>()
    val handover = LibraryHandover()
    private var drafts: DocumentDrafts? = null

    /**
     * The session's drafts, made by [create] the first time they are asked for: empty for a library
     * opened afresh, the saved ones in a restored process. Every later call gets the same drafts, so
     * a save still in flight when the window is recreated settles into the drafts the new one shows.
     */
    fun drafts(create: () -> DocumentDrafts): DocumentDrafts = drafts ?: create().also { drafts = it }

    /** Clear its screens' viewmodels, and with them the documents' they held. */
    fun clear() {
        viewModelStore.clear()
        documents.clear()
    }
}

/**
 * What the open library's UI keeps ([LibrarySessionUi]), one per session. Held in the window's store
 * (the activity's on Android), so an activity recreated for a configuration change finds its
 * library's screens as they were. [keepOnly] clears every other session's, so nothing a closed
 * library's screens started runs on against it.
 */
internal class LibraryViewModelStores : ViewModel() {
    private val sessions = HashMap<LibrarySession, LibrarySessionUi>()

    fun of(session: LibrarySession): LibrarySessionUi = sessions.getOrPut(session) { LibrarySessionUi() }

    fun keepOnly(session: LibrarySession?) {
        for (gone in sessions.keys.filter { it != session }) sessions.remove(gone)?.clear()
    }

    override fun onCleared() = keepOnly(null)
}

/**
 * [content] in the app's [language] ([LocaleProvider]), handed the open library's [LibraryUi], or
 * null while no library is open. A change of language rebuilds everything below [LocaleProvider],
 * so what must outlive it is remembered here, above it, for as long as [session] is open. Once the
 * UI of a session that left is gone, its screens' viewmodels are cleared.
 */
@Composable
internal fun LocalizedApp(
    language: String?,
    session: LibrarySession?,
    scope: CoroutineScope,
    stores: LibraryViewModelStores = viewModel { LibraryViewModelStores() },
    content: @Composable (LibraryUi?) -> Unit,
) {
    val library = session?.let { rememberLibraryUi(it, scope, stores.of(it)) }
    // Side effects run once the composition has let go of what it no longer shows.
    SideEffect { stores.keepOnly(session) }
    LocaleProvider(language) { content(library) }
}

@Composable
private fun rememberLibraryUi(
    session: LibrarySession,
    scope: CoroutineScope,
    held: LibrarySessionUi,
): LibraryUi {
    // The session keeps its drafts ([held]); they are saved only for a restored process, whose
    // [held] is new and starts from them. A recreated window finds the ones it had, saves in flight
    // included, and its restore leaves them be.
    val drafts =
        rememberSaveable(
            held,
            saver =
                Saver(
                    save = { it.serialize() },
                    restore = { saved -> held.drafts { DocumentDrafts(session.meta, scope, saved) } },
                ),
        ) { held.drafts { DocumentDrafts(session.meta, scope) } }
    // A controller of the session's own, as its back stack holds the session's viewmodel stores: a
    // new one for another session, and the saved one again for the same session in a recreated
    // activity or a restored process. Saved, so the restore finds the key it was saved under.
    val navKey = rememberSaveable(session) { Random.nextLong() }
    val nav = key(navKey) { rememberNavController() }
    return remember(held, drafts, nav, scope) { LibraryUi(held, drafts, nav, scope) }
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
    whatsNew: WhatsNewGateViewModel = viewModel { WhatsNewGateViewModel(env.settings, log = env.log) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val showWhatsNew by whatsNew.show.collectAsStateWithLifecycle()
    val useSaf by vm.useSafStorage.collectAsStateWithLifecycle()
    val language by vm.appLanguage.collectAsStateWithLifecycle()
    // Above the language switch, which rebuilds everything below it.
    val firstRun = rememberFirstRunHost()
    // Who takes files dropped on the window instead of the open library's import: first run, while
    // it asks for the first document.
    var dropTarget by remember { mutableStateOf<((List<Pair<ExternalSource, String>>) -> Unit)?>(null) }
    LocalizedApp(language, (state as? AppState.Open)?.session, env.appScope) { library ->
        AppSurface {
            val snackbar = rememberSnackbarMessages(vm.messages)
            // Files dropped on the desktop window import into whichever library is open, from any screen;
            // the message's Show opens the library on the new documents. While first run asks for the
            // first document, dropped PDFs go to it instead.
            val currentLibrary by rememberUpdatedState(library)
            LaunchedEffect(vm) {
                DesktopBridge.droppedFiles.receiveAsFlow().collect { request ->
                    val firstRun = dropTarget
                    if (firstRun != null && request.images.isEmpty()) {
                        firstRun(request.pdfs)
                    } else {
                        vm.importFiles(request) { docIds -> currentLibrary?.showDocuments(docIds) }
                    }
                }
            }
            // Without a library there are no screens to title the window; the app's name, in its language.
            if (state !is AppState.Open) PublishWindowTitle(stringResource(Res.string.common_app_name))

            Box(modifier.fillMaxSize()) {
                when (val current = state) {
                    AppState.Restoring, AppState.Opening -> {
                        LoadingScreen()
                    }

                    is AppState.NoLibrary -> {
                        val cloudState by vm.cloudSetupState.collectAsStateWithLifecycle()
                        OnboardingScreen(
                            failure = current.failure,
                            useSaf = useSaf,
                            onUseSafChange = vm::setUseSafStorage,
                            onOpen = vm::open,
                            onCreate = vm::createLibrary,
                            onUseRecommendedFolder = vm::useRecommended,
                            cloudState = cloudState,
                            cloudSetup = vm.cloudSetup.takeIf { vm.cloudAvailable },
                            onBegin = firstRun::begin,
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
                        val queue by vm.queueStatus.collectAsStateWithLifecycle()
                        val writeBlock by vm.writeBlock.collectAsStateWithLifecycle()
                        val writeBlockActions =
                            remember(vm) {
                                object : WriteBlockActions {
                                    override fun retry() = vm.retryWrites()

                                    override fun rebuildIndex() = vm.rebuildIndex()
                                }
                            }
                        // Never null here: LocalizedApp remembers it for the session this state opened.
                        // Keyed, so nothing remembered for one library's screens carries over to
                        // another's when the session changes with no screen in between.
                        if (library != null) {
                            key(library) {
                                val path = firstRun.path
                                if (path != null) {
                                    FirstRun(
                                        env,
                                        current.session,
                                        library,
                                        firstRun,
                                        path,
                                        snackbar,
                                    ) { dropTarget = it }
                                } else {
                                    DocumentDraftHost(library.drafts) {
                                        MainNav(
                                            settings = env.settings,
                                            session = current.session,
                                            library = library,
                                            aiConfigured = aiConfigured,
                                            queue = queue,
                                            writeBlock = writeBlock,
                                            snackbar = snackbar,
                                            onOpenLibrary = vm::open,
                                            writeBlockActions = writeBlockActions,
                                        )
                                    }
                                    firstRun.landing?.let { exit ->
                                        DisposableEffect(exit) {
                                            library.landOn(exit)
                                            if (exit is FirstRunExit.Document) vm.firstDocumentLanded(exit.text)
                                            firstRun.landing = null
                                            onDispose {}
                                        }
                                    }
                                }
                            }
                        }
                        // A switch to another library that failed lands back in this one; say why.
                        current.failure?.let { OpenFailedDialog(it, onDismiss = vm::dismissFailure) }
                    }
                }
                // A version change shows the bundled notes once, apart from the update banner.
                if (showWhatsNew && state is AppState.Open) {
                    WhatsNewDialog(onDismiss = whatsNew::dismiss)
                }
                // Files dragged over the desktop window: the whole window is the drop target.
                val dragging by DesktopBridge.dragging.collectAsStateWithLifecycle()
                if (dragging) DropOverlay()
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
}

/**
 * First run's steps once its library is open ([FirstRunScreen]), with their viewmodel kept in the
 * library's store, so a recreated window finds them where they were. Files dropped on the window go
 * where [onDropTarget] says while a step takes them.
 */
@Composable
private fun FirstRun(
    env: AppEnvironment,
    session: LibrarySession,
    library: LibraryUi,
    host: FirstRunHost,
    path: FirstRunPath,
    snackbar: SnackbarHostState,
    onDropTarget: (((List<Pair<ExternalSource, String>>) -> Unit)?) -> Unit,
) {
    LibraryViewModelHost(library) {
        FirstRunScreen(
            session = session,
            settings = env.settings,
            readOcrModels = env::ocrModels,
            path = path,
            step = host.step,
            onStep = { host.step = it },
            onFinish = host::finish,
            snackbar = snackbar,
            onDropTarget = onDropTarget,
        )
    }
}

/** Take the user where first run left them ([exit]), once the library's screens show. */
private fun LibraryUi.landOn(exit: FirstRunExit) {
    when (exit) {
        is FirstRunExit.Document -> {
            browse.setOrder(listOf(exit.docId))
            nav.navigate(Route.DocDetail(exit.docId))
        }

        FirstRunExit.WatchFolder -> {
            nav.navigate(Route.Settings(SettingsCategoryId.IMPORT.routeKey))
        }

        // The library is where the shell starts.
        FirstRunExit.Library -> {}
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
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(Res.string.common_opening_library), Modifier.padding(top = 16.dp))
        }
    }
}
