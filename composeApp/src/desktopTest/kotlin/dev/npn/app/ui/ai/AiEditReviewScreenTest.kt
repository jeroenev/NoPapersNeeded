@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.EditRunReview
import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import io.kotest.matchers.collections.shouldContainExactly
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The queued run's Review against a real composition. */
class AiEditReviewScreenTest {
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

    /** Opening a document goes where the screen was last told to send it, not where it first was. */
    @Test
    fun openingADocumentUsesTheCurrentCallback() =
        onUiThread {
            val opened = mutableListOf<String>()
            var onOpenDoc by mutableStateOf<(String) -> Unit>({ opened += "first:$it" })
            val session = FakeLibrarySession().apply { aiEditQueue.reviews["job"] = OneDocReview }
            val scene = scene(session) { onOpenDoc }
            try {
                scene.render()
                onOpenDoc = { opened += "second:$it" }
                scene.render()
                scene.click { it.contentDescription() == "Open document" }
                opened shouldContainExactly listOf("second:a")
            } finally {
                scene.close()
            }
        }

    private object OneDocReview : EditRunReview {
        override val jobId = "job"
        override val docs =
            listOf(
                ReviewDoc(
                    docId = "a",
                    title = "Invoice",
                    fileName = "a.pdf",
                    snippet = null,
                    chips = listOf(ReviewChip("invoice", isNew = false, selected = true)),
                    removals = emptyList(),
                    fields = emptyList(),
                ),
            )
        override val emptyDocs = emptyList<ReviewDoc>()
        override val failures = 0

        override suspend fun apply(reviewed: List<ReviewDoc>): ApplyOutcome = ApplyOutcome.NothingSelected

        override suspend fun discard() = Unit

        override fun landedSoFar() = ApplyResult(0, 0, moreRemain = false)
    }

    private fun scene(
        session: FakeLibrarySession,
        onOpenDoc: () -> (String) -> Unit,
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
            ) {
                NpnTheme(darkTheme = false) {
                    AiEditReviewScreen(session = session, jobId = "job", onBack = {}, onOpenDoc = onOpenDoc())
                }
            }
        }
    }

    private fun SemanticsNode.contentDescription(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    /** Click the nearest clickable node at or above the first node [match] finds. */
    private fun ImageComposeScene.click(match: (SemanticsNode) -> Boolean) {
        generateSequence(nodes().first(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick]
            .action
            ?.invoke()
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
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
