package dev.npn.app.ui.settings

import dev.npn.core.sync.CloudSetup
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupStep
import kotlinx.coroutines.CancellationException
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

    /** Browsing [remote] at [path] for the folder this library should mirror to. */
    data class Folders(
        val remote: String,
        val path: String,
        val folders: List<String> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    ) : CloudSetupState

    /** The wizard could not continue; the pane shows [message] with a Retry. */
    data class Failed(
        val message: String,
    ) : CloudSetupState
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

    /** Create [name] under the browsed folder, then refresh the listing. */
    fun createFolder(name: String)

    /** Keep the browsed folder as this library's remote and close the wizard. */
    fun useFolder()

    /** Re-open folder picking for the configured remote, without creating a new one. */
    fun changeFolder()

    /** Forget the remote and turn automatic sync off, so a cleared remote cannot leave it stuck on. */
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
    private val clearRemote: () -> Unit,
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
        wizardJob?.cancel()
        wizardJob =
            scope.launch {
                _state.value = CloudSetupState.Loading
                _state.value =
                    try {
                        CloudSetupState.Providers(client.providers())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        CloudSetupState.Failed(e.message ?: e::class.simpleName ?: "error")
                    }
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
                    try {
                        if (question == null) {
                            client.begin(
                                name = form.provider.type,
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SetupStep.Failed(e.message ?: e::class.simpleName ?: "error")
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
                    try {
                        client.begin(
                            name = provider.type,
                            type = provider.type,
                            params = values.filterValues { it.isNotBlank() },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SetupStep.Failed(e.message ?: e::class.simpleName ?: "error")
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
            scope.launch {
                try {
                    setup()?.stopOauth()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Backing out is best-effort: the engine may already have finished sign-in.
                }
            }
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
                _state.value = folders.copy(busy = true, error = null)
                val failure =
                    try {
                        client.makeFolder(folders.remote, path)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    }
                if (failure == null) {
                    refreshFolders(folders.remote, folders.path)
                } else {
                    _state.value = folders.copy(error = failure.message ?: failure::class.simpleName ?: "error")
                }
            }
    }

    override fun useFolder() {
        val folders = _state.value as? CloudSetupState.Folders ?: return
        persistRemote(folders.remote + folders.path)
        _state.value = null
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
        clearRemote()
        _state.value = null
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
                _state.value = CloudSetupState.Failed(step.message)
            }
        }
    }

    /** Answer a question rclone asked that the wizard supplies itself, then continue the conversation. */
    private suspend fun answerAutomatically(
        question: SetupStep.Question,
        result: String,
    ) {
        val client = setup() ?: return
        val step =
            try {
                client.answer(question.remoteName, question.state, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SetupStep.Failed(e.message ?: e::class.simpleName ?: "error")
            }
        handleStep(step)
    }

    private fun showQuestion(question: SetupStep.Question) {
        val provider = activeProvider
        if (provider == null) {
            _state.value = CloudSetupState.Failed(question.error ?: "no provider is being configured")
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
                        val status =
                            try {
                                client.oauthStatus()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // A poll the engine cannot answer yet is not fatal; try again next tick.
                                null
                            }
                        val url = status?.authUrl
                        if (url != null) {
                            _state.value = CloudSetupState.SigningIn(providerName = providerName, authUrl = url)
                        }
                        delay(OAUTH_POLL_MS)
                    }
                }
            val step =
                try {
                    client.answer(question.remoteName, question.state, OAUTH_USE_BROWSER)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SetupStep.Failed(e.message ?: e::class.simpleName ?: "error")
                }
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
            try {
                CloudSetupState.Folders(remote = remote, path = path, folders = client.folders(remote, path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CloudSetupState.Folders(
                    remote = remote,
                    path = path,
                    error = e.message ?: e::class.simpleName ?: "error",
                )
            }
    }
}

/**
 * Whether a question is rclone's "use a browser to sign in" prompt, which the wizard answers itself.
 * rclone marks it by option name on some backends and only by the state it carries on others.
 */
private fun SetupStep.Question.isBrowserSignIn(): Boolean =
    option.name == "config_is_local" || state.contains("oauth", ignoreCase = true)
