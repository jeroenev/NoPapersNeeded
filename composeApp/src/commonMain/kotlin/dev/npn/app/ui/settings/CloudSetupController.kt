package dev.npn.app.ui.settings

import dev.npn.core.sync.CloudSetup
import dev.npn.core.sync.FolderContents
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupFailure
import dev.npn.core.sync.SetupStep
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How long the sign-in status is polled while the (blocking) answer is in flight. */
private const val OAUTH_POLL_MS = 1_000L

/** What rclone's "use a browser" question is answered with. */
private const val OAUTH_USE_BROWSER = "true"

/**
 * Questions the wizard answers on the user's behalf, so setup stays a single sign-in prompt.
 *
 * Drive asks whether to keep using rclone's built-in OAuth app before it opens the browser; saying
 * yes keeps setup to one click. Declining turns `client_id`/`client_secret` into required fields,
 * the bring-your-own-app path the wizard deliberately does not offer.
 */
private val AUTOMATIC_ANSWERS = mapOf("config_shared_client_id" to "true")

/**
 * Where the in-app cloud setup is. Null is idle (the pane shows its Connect prompt); every other
 * branch is the one screen the wizard renders. Values travel in the states rather than in the
 * controller so a recomposition cannot show a stale step, and every provider is handled by the same
 * state machine because rclone hands back its own option metadata.
 */
sealed interface CloudSetupState {
    /** Asking the engine for its provider catalog. */
    data object Loading : CloudSetupState

    /** Picking a provider from the catalog. */
    data class Providers(
        val providers: List<RcloneProvider>,
    ) : CloudSetupState

    /**
     * Filling in a form: a provider's initial options ([question] null) or one follow-up question
     * rclone asked ([question] set, whose single option is the only field shown).
     */
    data class Form(
        val provider: RcloneProvider,
        val values: Map<String, String>,
        val question: SetupStep.Question? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : CloudSetupState

    /**
     * Waiting on the browser sign-in. [authUrl] is filled in by the status poll once the engine has
     * published one, so the pane can offer it before the blocking answer returns.
     */
    data class SigningIn(
        val providerName: String? = null,
        val authUrl: String? = null,
    ) : CloudSetupState

    /**
     * Browsing [remote] at [path] for the folder this library should mirror to. [problem] is why the
     * last step on this screen did not go through, with the engine's own [detail] where it gave one.
     */
    data class Folders(
        val remote: String,
        val path: String,
        val folders: List<String> = emptyList(),
        val busy: Boolean = false,
        val problem: FolderProblem? = null,
        val detail: String? = null,
    ) : CloudSetupState {
        /** The top level of a remote is never a library's folder: it is everything the user stores. */
        val usable: Boolean get() = path.isNotEmpty() && !busy
    }

    /** The wizard could not continue; the pane says why ([reason], [detail]) with a Retry. */
    data class Failed(
        val reason: SetupFailure,
        val detail: String? = null,
    ) : CloudSetupState
}

/** Why a step of the folder picker did not go through. */
enum class FolderProblem {
    /** The folders here could not be listed. */
    LIST_FAILED,

    /** The new folder could not be created. */
    CREATE_FAILED,

    /** What the chosen folder holds could not be checked. */
    CHECK_FAILED,

    /** The chosen folder holds other files and no library; sync would take them all in. */
    OTHER_FILES,
}

/**
 * What the cloud setup wizard can be asked to do: each call moves its [CloudSetupState] on. The
 * pane reports the user's intent here and renders whatever step comes back.
 */
interface CloudSetupActions {
    /** Start (or restart) the wizard by asking the engine which providers it can configure. */
    fun start()

    /** Choose a provider and open its form, pre-seeded with rclone's defaults. */
    fun chooseProvider(provider: RcloneProvider)

    /** Edit one field of the open form or question. */
    fun setValue(
        option: String,
        value: String,
    )

    /** Submit the open form, or answer the question rclone last asked. */
    fun submit()

    /** Back out of the wizard, abandoning any browser sign-in it had started. */
    fun cancel()

    /** Descend into [name] beneath the folder being browsed. */
    fun openFolder(name: String)

    /** Go up one level; a no-op at the remote root. */
    fun openFolderUp()

    /** Create [name] under the browsed folder and open it. */
    fun createFolder(name: String)

    /**
     * Keep the browsed folder as this library's remote and close the wizard, once it proves empty or
     * a library's: a folder of other files is refused.
     */
    fun useFolder()

    /** Re-open folder picking for the configured remote, without creating a new one. */
    fun changeFolder()

    /**
     * Stop syncing this library: forget the remote and its sign-in on this device, and turn automatic
     * sync off. Nothing is deleted, here or in the cloud.
     */
    fun disconnect()
}

/**
 * The cloud setup wizard's state machine, split out of [SettingsViewModel] so neither class carries
 * more functions than detekt allows. It owns the conversation with [CloudSetup] and nothing else:
 * the scope and the session knobs (the setup engine, the current remote, keeping and clearing it)
 * are injected, so the controller holds no reference to the view model, settings store or session.
 */
internal class CloudSetupController(
    private val scope: CoroutineScope,
    private val setup: () -> CloudSetup?,
    private val readRemote: suspend () -> String?,
    private val persistRemote: (String) -> Unit,
    /** Forget the library's remote (the configured one, passed in) and its sign-in. */
    private val disconnectRemote: (String) -> Unit,
    /** A fresh, unique name for a remote of a provider type, so no setup overwrites another's. */
    private val newRemoteName: (type: String) -> String,
) : CloudSetupActions {
    private val _state = MutableStateFlow<CloudSetupState?>(null)

    /** The wizard's current step, or null while it is closed. */
    val state: StateFlow<CloudSetupState?> = _state.asStateFlow()

    /** Whether this device wired an in-app setup; false means the pane can only explain itself. */
    val available: Boolean get() = setup() != null

    /** The provider the conversation is with; rclone tracks its own state, the pane needs the row. */
    private var activeProvider: RcloneProvider? = null

    private var wizardJob: Job? = null
    private var folderJob: Job? = null

    override fun start() {
        val client = setup() ?: return
        val wasSigningIn = _state.value is CloudSetupState.SigningIn
        wizardJob?.cancel()
        folderJob?.cancel()
        wizardJob =
            scope.launch {
                _state.value = CloudSetupState.Loading
                if (wasSigningIn) runCatchingCancellable { client.stopOauth() }
                _state.value =
                    runCatchingCancellable { CloudSetupState.Providers(client.providers()) }
                        .getOrElse { CloudSetupState.Failed(SetupFailure.ENGINE, it.reason()) }
            }
    }

    override fun chooseProvider(provider: RcloneProvider) {
        activeProvider = provider
        val values = provider.options.associate { it.name to it.defaultValue.orEmpty() }
        if (provider.browserSignIn) {
            // Nothing to fill in: rclone only has to hear "use the browser", so go straight to the
            // sign-in screen instead of a form that would only hold OAuth-app credentials.
            _state.value = CloudSetupState.SigningIn(providerName = provider.name)
            begin(provider, values)
        } else {
            _state.value = CloudSetupState.Form(provider = provider, values = values)
        }
    }

    override fun setValue(
        option: String,
        value: String,
    ) {
        val form = _state.value as? CloudSetupState.Form ?: return
        _state.value = form.copy(values = form.values + (option to value), error = null)
    }

    override fun submit() {
        val form = _state.value as? CloudSetupState.Form ?: return
        val client = setup() ?: return
        wizardJob?.cancel()
        wizardJob =
            scope.launch {
                _state.value = form.copy(busy = true, error = null)
                val question = form.question
                val step =
                    stepOrFailure {
                        if (question == null) {
                            client.begin(
                                name = newRemoteName(form.provider.type),
                                type = form.provider.type,
                                params = form.values.filterValues { it.isNotBlank() },
                            )
                        } else {
                            client.answer(
                                name = question.remoteName,
                                state = question.state,
                                result = form.values[question.option.name].orEmpty(),
                            )
                        }
                    }
                handleStep(step)
            }
    }

    /** Start a provider's conversation without a form — a browser sign-in has no fields to collect. */
    private fun begin(
        provider: RcloneProvider,
        values: Map<String, String>,
    ) {
        val client = setup() ?: return
        wizardJob?.cancel()
        wizardJob =
            scope.launch {
                val step =
                    stepOrFailure {
                        client.begin(
                            name = newRemoteName(provider.type),
                            type = provider.type,
                            params = values.filterValues { it.isNotBlank() },
                        )
                    }
                handleStep(step)
            }
    }

    override fun cancel() {
        wizardJob?.cancel()
        wizardJob = null
        folderJob?.cancel()
        folderJob = null
        val wasSigningIn = _state.value is CloudSetupState.SigningIn
        _state.value = null
        if (wasSigningIn) {
            // Backing out is best-effort: the engine may already have finished sign-in.
            scope.launch { runCatchingCancellable { setup()?.stopOauth() } }
        }
    }

    override fun openFolder(name: String) {
        val folders = _state.value as? CloudSetupState.Folders ?: return
        val child = if (folders.path.isEmpty()) name else "${folders.path}/$name"
        loadFolders(folders.remote, child)
    }

    override fun openFolderUp() {
        val folders = _state.value as? CloudSetupState.Folders ?: return
        if (folders.path.isEmpty()) return
        loadFolders(folders.remote, folders.path.substringBeforeLast('/', ""))
    }

    override fun createFolder(name: String) {
        val folders = _state.value as? CloudSetupState.Folders ?: return
        val client = setup() ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val path = if (folders.path.isEmpty()) trimmed else "${folders.path}/$trimmed"
        folderJob?.cancel()
        folderJob =
            scope.launch {
                _state.value = folders.copy(busy = true, problem = null, detail = null)
                runCatchingCancellable { client.makeFolder(folders.remote, path) }.fold(
                    // Opened at once: "Use this folder" then means the folder just made.
                    onSuccess = { refreshFolders(folders.remote, path) },
                    onFailure = {
                        _state.value =
                            folders.copy(problem = FolderProblem.CREATE_FAILED, detail = it.reason())
                    },
                )
            }
    }

    override fun useFolder() {
        val folders = _state.value as? CloudSetupState.Folders ?: return
        val client = setup() ?: return
        if (!folders.usable) return
        folderJob?.cancel()
        folderJob =
            scope.launch {
                _state.value = folders.copy(busy = true, problem = null, detail = null)
                runCatchingCancellable { client.folderContents(folders.remote, folders.path) }.fold(
                    onSuccess = { contents ->
                        if (contents == FolderContents.OTHER_FILES) {
                            _state.value = folders.copy(problem = FolderProblem.OTHER_FILES)
                        } else {
                            persistRemote(folders.remote + folders.path)
                            _state.value = null
                        }
                    },
                    onFailure = {
                        _state.value = folders.copy(problem = FolderProblem.CHECK_FAILED, detail = it.reason())
                    },
                )
            }
    }

    override fun changeFolder() {
        folderJob?.cancel()
        folderJob =
            scope.launch {
                val configured = readRemote().orEmpty()
                if (configured.isBlank()) return@launch
                // `name:path`, or a bare connection string (`:s3,provider=…`) that has no folder
                // part; a colon at position 0 is the latter, not an empty remote name.
                val colon = configured.indexOf(':')
                val remote = if (colon > 0) configured.substring(0, colon + 1) else configured
                val path = if (colon > 0) configured.substring(colon + 1).trim('/') else ""
                refreshFolders(remote, path)
            }
    }

    override fun disconnect() {
        folderJob?.cancel()
        folderJob =
            scope.launch {
                _state.value = null
                val configured = readRemote() ?: return@launch
                disconnectRemote(configured)
            }
    }

    /** Route one rclone step: browser sign-in, a question to render, the folder picker, or a failure. */
    private suspend fun handleStep(step: SetupStep) {
        when (step) {
            is SetupStep.Question -> {
                when {
                    step.isBrowserSignIn() -> {
                        runBrowserSignIn(step)
                    }

                    step.option.name in AUTOMATIC_ANSWERS -> {
                        answerAutomatically(
                            step,
                            AUTOMATIC_ANSWERS.getValue(step.option.name),
                        )
                    }

                    else -> {
                        showQuestion(step)
                    }
                }
            }

            is SetupStep.Done -> {
                openRoot(step.remoteName)
            }

            is SetupStep.Failed -> {
                _state.value = CloudSetupState.Failed(step.reason, step.detail)
            }
        }
    }

    /** Answer a question rclone asked that the wizard supplies itself, then continue the conversation. */
    private suspend fun answerAutomatically(
        question: SetupStep.Question,
        result: String,
    ) {
        val client = setup() ?: return
        handleStep(stepOrFailure { client.answer(question.remoteName, question.state, result) })
    }

    private fun showQuestion(question: SetupStep.Question) {
        val provider = activeProvider
        if (provider == null) {
            _state.value = CloudSetupState.Failed(SetupFailure.NOT_IN_PROGRESS, question.error)
            return
        }
        val values = (_state.value as? CloudSetupState.Form)?.values.orEmpty().toMutableMap()
        if (question.option.name !in values) values[question.option.name] = question.option.defaultValue.orEmpty()
        _state.value =
            CloudSetupState.Form(
                provider = provider,
                values = values,
                question = question,
                error = question.error,
            )
    }

    /**
     * Answer the browser-sign-in question with "true" and wait for the (blocking) result, polling
     * the engine's sign-in status meanwhile so the pane can offer the URL before rclone returns.
     */
    private suspend fun runBrowserSignIn(question: SetupStep.Question) =
        coroutineScope {
            val client = setup() ?: return@coroutineScope
            val providerName = activeProvider?.name
            _state.value = CloudSetupState.SigningIn(providerName = providerName)
            val poll =
                launch {
                    while (isActive) {
                        // A poll the engine cannot answer yet is not fatal; try again next tick.
                        val status = runCatchingCancellable { client.oauthStatus() }.getOrNull()
                        val url = status?.authUrl
                        if (url != null) {
                            _state.value = CloudSetupState.SigningIn(providerName = providerName, authUrl = url)
                        }
                        delay(OAUTH_POLL_MS)
                    }
                }
            val step = stepOrFailure { client.answer(question.remoteName, question.state, OAUTH_USE_BROWSER) }
            poll.cancel()
            handleStep(step)
        }

    private fun openRoot(name: String) {
        loadFolders("$name:", "")
    }

    private fun loadFolders(
        remote: String,
        path: String,
    ) {
        folderJob?.cancel()
        folderJob = scope.launch { refreshFolders(remote, path) }
    }

    private suspend fun refreshFolders(
        remote: String,
        path: String,
    ) {
        val client = setup() ?: return
        val previous = (_state.value as? CloudSetupState.Folders)?.folders.orEmpty()
        _state.value = CloudSetupState.Folders(remote = remote, path = path, folders = previous, busy = true)
        _state.value =
            runCatchingCancellable {
                CloudSetupState.Folders(remote = remote, path = path, folders = client.folders(remote, path))
            }.getOrElse {
                CloudSetupState.Folders(
                    remote = remote,
                    path = path,
                    problem = FolderProblem.LIST_FAILED,
                    detail = it.reason(),
                )
            }
    }

    /** The step [call] returns, or the engine's failure as one. */
    private suspend fun stepOrFailure(call: suspend () -> SetupStep): SetupStep =
        runCatchingCancellable { call() }.getOrElse { SetupStep.Failed(SetupFailure.ENGINE, it.reason()) }
}

/**
 * Whether a question is rclone's "use a browser to sign in" prompt, which the wizard answers itself.
 * rclone marks it by option name on some backends and only by the state it carries on others.
 */
private fun SetupStep.Question.isBrowserSignIn(): Boolean =
    option.name == "config_is_local" || state.contains("oauth", ignoreCase = true)
