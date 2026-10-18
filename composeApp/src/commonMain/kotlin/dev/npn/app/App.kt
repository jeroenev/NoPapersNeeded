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
import dev.npn.app.ui.detail.rememberDocumentDrafts
import dev.npn.app.ui.nav.MainNav
import dev.npn.app.ui.onboarding.OnboardingScreen
import dev.npn.app.ui.onboarding.OpenFailureMessage
import dev.npn.app.ui.onboarding.RestoreFailedScreen
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
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
 * What the open library's UI keeps when the app's language changes: its unsaved [drafts], where its
 * navigation stands ([nav]), its screens' viewmodels ([viewModels]), and, through [handover], the
 * order the detail screen walks and what one screen hands another as it navigates there. All of it
 * belongs to one session: another library gets its own.
 */
@Stable
internal class LibraryUi(
    val drafts: DocumentDrafts,
    val nav: NavHostController,
    val viewModels: ViewModelStoreOwner,
    handover: LibraryHandover,
) {
    val browse = handover.browse
    val libraryFilter = handover.libraryFilter
    val aiEditSelection = handover.aiEditSelection
}

/**
 * What the open library's screens hand each other as they navigate. Kept beside the session's
 * viewmodels ([LibraryViewModelStores]), so a window recreated for a configuration change still has
 * it, and dropped with them. A process restore starts without it, as a screen opened afresh would.
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
 * The viewmodel stores of the open library's screens, and what they hand each other, one per
 * session. Held in the window's store (the activity's on Android), so an activity recreated for a
 * configuration change finds its library's screens as they were. [keepOnly] clears every other
 * session's store, so nothing a closed library's screens started runs on against it.
 */
internal class LibraryViewModelStores : ViewModel() {
    private val stores = KeyedViewModelStores<LibrarySession>()
    private val handovers = HashMap<LibrarySession, LibraryHandover>()

    fun of(session: LibrarySession): ViewModelStoreOwner = stores.of(session)

    fun handoverOf(session: LibrarySession): LibraryHandover = handovers.getOrPut(session) { LibraryHandover() }

    fun keepOnly(session: LibrarySession?) {
        stores.keepOnly(session)
        handovers.keys.retainAll { it == session }
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
    val library = session?.let { rememberLibraryUi(it, scope, stores.of(it), stores.handoverOf(it)) }
    // Side effects run once the composition has let go of what it no longer shows.
    SideEffect { stores.keepOnly(session) }
    LocaleProvider(language) { content(library) }
}

@Composable
private fun rememberLibraryUi(
    session: LibrarySession,
    scope: CoroutineScope,
    viewModels: ViewModelStoreOwner,
    handover: LibraryHandover,
): LibraryUi {
    val drafts = rememberDocumentDrafts(session, scope)
    // A controller of the session's own, as its back stack holds the session's viewmodel stores: a
    // new one for another session, and the saved one again for the same session in a recreated
    // activity or a restored process. Saved, so the restore finds the key it was saved under.
    val navKey = rememberSaveable(session) { Random.nextLong() }
    val nav = key(navKey) { rememberNavController() }
    return remember(drafts, nav, viewModels, handover) { LibraryUi(drafts, nav, viewModels, handover) }
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
    val language by vm.appLanguage.collectAsStateWithLifecycle()
    LocalizedApp(language, (state as? AppState.Open)?.session, env.appScope) { library ->
        AppSurface {
            val snackbar = rememberSnackbarMessages(vm.messages)
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
                        val queue by vm.queueStatus.collectAsStateWithLifecycle()
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
                                        queue = queue,
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
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(Res.string.common_opening_library), Modifier.padding(top = 16.dp))
        }
    }
}
