@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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

/** A fresh store for a scene's viewmodels. */
internal fun viewModelStoreOwner(): ViewModelStoreOwner =
    object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

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

/** Replace the text of the scene's first editable field with [value], and render. */
internal fun ImageComposeScene.type(value: String) {
    nodes()
        .firstOrNull { it.isEditable() }
        ?.config
        ?.get(SemanticsActions.SetText)
        ?.action
        ?.invoke(AnnotatedString(value))
    render()
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
