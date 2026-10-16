@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.rules.AutoMatchRulesScreen
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.TagInfo
import io.kotest.matchers.shouldBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What the tags and rules screens are in the middle of comes back when their state is saved and
 * restored: a trip to another tab and back, or the process being restored.
 */
class ScreenRestorationTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun pinEnglishLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun aTagSelectionSurvivesRestoration() =
        onUiThread {
            val session = tagSession()
            restored(
                content = { TagManagementScreen(session) },
                before = { scene ->
                    scene.longClick { it.text() == "tax" }
                    scene.longClick { it.text() == "invoice" }
                    scene.has { it.text() == "2 selected" } shouldBe true
                },
                after = { scene -> scene.has { it.text() == "2 selected" } shouldBe true },
            )
        }

    @Test
    fun anOpenTagRenameKeepsItsTypedName() =
        onUiThread {
            val session = tagSession()
            restored(
                content = { TagManagementScreen(session) },
                before = { scene ->
                    scene.click { it.text() == "tax" }
                    scene.click { it.text() == "Rename" }
                    scene.type("taxes")
                },
                after = { scene ->
                    scene.has { it.text() == "Rename tag" } shouldBe true
                    scene.field()?.editableText() shouldBe "taxes"
                },
            )
        }

    @Test
    fun anOpenRuleFormKeepsWhatWasTyped() =
        onUiThread {
            val session = FakeLibrarySession()
            restored(
                content = { AutoMatchRulesScreen(session, onBack = {}) },
                before = { scene ->
                    scene.click { it.contentDescription() == "Add rule" }
                    scene.type("Utilities")
                },
                after = { scene ->
                    scene.has { it.text() == "New rule" } shouldBe true
                    scene.field()?.editableText() shouldBe "Utilities"
                },
            )
        }

    private fun tagSession() =
        FakeLibrarySession().apply {
            tags.tags.value = listOf(TagInfo("invoice", null, null, 2), TagInfo("tax", null, null, 1))
        }

    /**
     * Run [before] on [content], save the screen's state, compose [content] again from it and run
     * [after] on that. Each composition gets its own viewmodels, as after a process restore.
     */
    private fun restored(
        content: @Composable () -> Unit,
        before: (ImageComposeScene) -> Unit,
        after: (ImageComposeScene) -> Unit,
    ) {
        val first = SaveableStateRegistry(restoredValues = null) { true }
        val beforeScene = scene(first, content)
        val saved =
            try {
                beforeScene.render()
                before(beforeScene)
                first.performSave()
            } finally {
                beforeScene.close()
            }
        val afterScene = scene(SaveableStateRegistry(restoredValues = saved) { true }, content)
        try {
            afterScene.render()
            afterScene.render()
            after(afterScene)
        } finally {
            afterScene.close()
        }
    }

    private fun scene(
        registry: SaveableStateRegistry,
        content: @Composable () -> Unit,
    ): ImageComposeScene {
        val lifecycle =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val viewModels =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        return ImageComposeScene(WIDTH, HEIGHT) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalViewModelStoreOwner provides viewModels,
                LocalSaveableStateRegistry provides registry,
            ) {
                NpnTheme(darkTheme = false) { content() }
            }
        }
    }

    private fun ImageComposeScene.type(value: String) {
        field()
            ?.config
            ?.get(SemanticsActions.SetText)
            ?.action
            ?.invoke(AnnotatedString(value))
        render()
    }

    private fun ImageComposeScene.field(): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null }

    private fun SemanticsNode.editableText(): String? = config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun SemanticsNode.contentDescription(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun SemanticsNode.text(): String? =
        config
            .getOrNull(SemanticsProperties.Text)
            ?.firstOrNull()
            ?.text

    private fun ImageComposeScene.has(match: (SemanticsNode) -> Boolean): Boolean = nodes().any(match)

    /** Click the nearest clickable node at or above the first node [match] finds. */
    private fun ImageComposeScene.click(match: (SemanticsNode) -> Boolean) {
        generateSequence(nodes().first(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick]
            .action
            ?.invoke()
        render()
    }

    /** Long-click the nearest long-clickable node at or above the first node [match] finds. */
    private fun ImageComposeScene.longClick(match: (SemanticsNode) -> Boolean) {
        generateSequence(nodes().first(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
            .config[SemanticsActions.OnLongClick]
            .action
            ?.invoke()
        render()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 900
    }
}
