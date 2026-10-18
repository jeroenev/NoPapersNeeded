@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedState
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.savedState
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.theme.NpnTheme
import javax.swing.SwingUtilities

// Building a scene and reading and driving it through its semantics, as the desktop UI tests do.

/**
 * A scene hosted as a window hosts the app: a resumed lifecycle and a store for its viewmodels,
 * plus whatever further [locals] the test provides. Pass [viewModels] to keep viewmodels across
 * scenes, as an activity's outlive its recreation.
 */
internal fun hostedScene(
    width: Int,
    height: Int,
    vararg locals: ProvidedValue<*>,
    density: Density = Density(1f),
    viewModels: ViewModelStoreOwner = viewModelStoreOwner(),
    content: @Composable () -> Unit,
): ImageComposeScene {
    val lifecycle =
        object : LifecycleOwner {
            override val lifecycle =
                LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
    return ImageComposeScene(width, height, density) {
        CompositionLocalProvider(
            LocalLifecycleOwner provides lifecycle,
            LocalViewModelStoreOwner provides viewModels,
            *locals,
            content = content,
        )
    }
}

/**
 * A scene showing [content] as a screen of the app shows it: in the app's light theme, on a
 * [Surface] that gives it the theme's background and text colours, in the app language
 * [appLocale] when one is given, with whatever further [locals] the test provides around the theme.
 * Unlike [hostedScene] it has no lifecycle or viewmodel store, for content that needs neither.
 */
internal fun themedScene(
    width: Int,
    height: Int,
    vararg locals: ProvidedValue<*>,
    appLocale: String? = null,
    density: Density = Density(1f),
    content: @Composable () -> Unit,
): ImageComposeScene =
    ImageComposeScene(width, height, density) {
        CompositionLocalProvider(*locals) {
            // The app language's provider is itself composable, so it is provided here, not passed in.
            if (appLocale == null) {
                NpnTheme(darkTheme = false) { Surface { content() } }
            } else {
                CompositionLocalProvider(LocalAppLocale provides appLocale) {
                    NpnTheme(darkTheme = false) { Surface { content() } }
                }
            }
        }
    }

/**
 * Run [block] on the scene and close it however the block ends, as the scene holds native
 * resources until it is closed. [ImageComposeScene] is not [AutoCloseable], so `use` does not apply.
 */
internal inline fun <T> ImageComposeScene.closing(block: ImageComposeScene.() -> T): T =
    try {
        block()
    } finally {
        close()
    }

/** A fresh store for a scene's viewmodels, which hands them saved state as a back-stack entry does. */
internal fun viewModelStoreOwner(): ScreenOwner = ScreenOwner()

/**
 * What a screen's back-stack entry is to its viewmodels: their store, and the saved state their
 * [androidx.lifecycle.SavedStateHandle]s come from ([androidx.lifecycle.createSavedStateHandle]).
 * [save] takes that state as the entry saves it for a restored process; a [ScreenOwner] built from
 * it ([restored]) hands each viewmodel its handle back, as the restored entry does.
 */
internal class ScreenOwner(
    restored: SavedState? = null,
) : ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory {
    override val lifecycle = LifecycleRegistry.createUnsafe(this)
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    // Every viewmodel here is built by its own initializer, which the extras below feed.
    override val defaultViewModelProviderFactory = viewModelFactory { }

    override val defaultViewModelCreationExtras: CreationExtras
        get() =
            MutableCreationExtras().apply {
                set(SAVED_STATE_REGISTRY_OWNER_KEY, this@ScreenOwner)
                set(VIEW_MODEL_STORE_OWNER_KEY, this@ScreenOwner)
            }

    init {
        controller.performAttach()
        enableSavedStateHandles()
        controller.performRestore(restored)
        lifecycle.currentState = Lifecycle.State.RESUMED
    }

    fun save(): SavedState = savedState().also(controller::performSave)
}

/** A [ScreenOwner] for a restored process: [saved]'s viewmodels get back what they saved, and nothing else. */
internal fun restored(saved: ScreenOwner): ScreenOwner = ScreenOwner(saved.save())

/** Every semantics node of the scene, its popups and dialogs included. */
internal fun ImageComposeScene.nodes(): List<SemanticsNode> {
    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
}

internal fun ImageComposeScene.node(match: (SemanticsNode) -> Boolean): SemanticsNode = nodes().first(match)

internal fun ImageComposeScene.has(match: (SemanticsNode) -> Boolean): Boolean = nodes().any(match)

/** Click the nearest clickable node at or above the first node [match] finds. */
internal fun ImageComposeScene.click(match: (SemanticsNode) -> Boolean) {
    generateSequence(node(match)) { it.parent }
        .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        .config[SemanticsActions.OnClick]
        .action
        ?.invoke()
}

/** The scene's first editable field, if it shows one. */
internal fun ImageComposeScene.editableField(): SemanticsNode? = nodes().firstOrNull { it.isEditable() }

/** Replace the text of the scene's first editable field with [value], and render. */
internal fun ImageComposeScene.type(value: String) {
    editableField()
        ?.config
        ?.get(SemanticsActions.SetText)
        ?.action
        ?.invoke(AnnotatedString(value))
    render()
}

/**
 * Give focus to the first node that can take it, and render, so a key sent next reaches it as it
 * would after a click. A scene starts with nothing focused, and keys then go nowhere.
 */
internal fun ImageComposeScene.focusFirst() {
    node { it.config.getOrNull(SemanticsActions.RequestFocus) != null }
        .config[SemanticsActions.RequestFocus]
        .action
        ?.invoke()
    render()
}

/**
 * Press and release [key], with Ctrl or Shift held if asked. It is sent to whatever holds the
 * keyboard; the key-event constructor is Compose's internal one, as a test has no native event.
 */
internal fun ImageComposeScene.press(
    key: Key,
    ctrl: Boolean = false,
    shift: Boolean = false,
) {
    sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift))
    sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isShiftPressed = shift))
}

internal fun SemanticsNode.isEditable(): Boolean = config.getOrNull(SemanticsProperties.EditableText) != null

internal fun SemanticsNode.hasError(): Boolean = config.getOrNull(SemanticsProperties.Error) != null

internal fun SemanticsNode.isEnabled(): Boolean = !config.contains(SemanticsProperties.Disabled)

internal fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

/** The node's first text, as a label or a button shows it. */
internal fun SemanticsNode.text(): String? = texts().firstOrNull()

/** What a text field holds, rather than its label. */
internal fun SemanticsNode.editableText(): String? = config.getOrNull(SemanticsProperties.EditableText)?.text

internal fun SemanticsNode.contentDescription(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

/** Run [block] on the UI thread, where a scene composes, and rethrow what it throws. */
internal fun onUiThread(block: () -> Unit) {
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
    failure?.let { throw it }
}
