@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import javax.swing.SwingUtilities

// Reading and driving a scene through its semantics, as the editor tests here do.

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

internal fun SemanticsNode.isEditable(): Boolean = config.getOrNull(SemanticsProperties.EditableText) != null

internal fun SemanticsNode.hasError(): Boolean = config.getOrNull(SemanticsProperties.Error) != null

internal fun SemanticsNode.isEnabled(): Boolean = !config.contains(SemanticsProperties.Disabled)

internal fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

internal fun SemanticsNode.contentDescription(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

/** Run [block] on the UI thread, where a scene composes, and rethrow what it throws. */
internal fun onUiThread(block: () -> Unit) {
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
    failure?.let { throw it }
}
