@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.click
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.EditRunReview
import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

/** The queued run's Review against a real composition. */
class AiEditReviewScreenTest : EnglishLocaleTest() {
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
    ): ImageComposeScene =
        hostedScene(WIDTH, HEIGHT) {
            NpnTheme(darkTheme = false) {
                AiEditReviewScreen(session = session, jobId = "job", onBack = {}, onOpenDoc = onOpenDoc())
            }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
