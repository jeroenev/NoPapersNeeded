@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.queue

import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.WorkState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Activity: the background bulk edits, problems first, and the cloud sync row. */
class QueueScreenTest : EnglishLocaleTest() {
    /** Answers already bought are not thrown away in one tap, as the interactive run's Cancel asks too. */
    @Test
    fun discardingARunWithAnswersAsksFirst() =
        onUiThread {
            val session = withRun(done = 2)
            scene(session).closing {
                render()
                click { it.text() == "Discard" }
                render()
                has { it.text() == "Stop this run?" } shouldBe true
                session.aiEditQueue.discarded.shouldBeEmpty()

                click { it.text() == "Stop" }
                render()
                session.aiEditQueue.discarded shouldContainExactly listOf("run")
            }
        }

    @Test
    fun aRunWithNothingBoughtYetIsDiscardedAtOnce() =
        onUiThread {
            val session = withRun(done = 0)
            scene(session).closing {
                render()
                click { it.text() == "Discard" }
                render()
                has { it.text() == "Stop this run?" } shouldBe false
                session.aiEditQueue.discarded shouldContainExactly listOf("run")
            }
        }

    private fun withRun(done: Int) =
        FakeLibrarySession().apply {
            work.editRunEntries.value =
                listOf(
                    QueuedEditRun(
                        jobId = "run",
                        total = 5,
                        done = done,
                        failures = 0,
                        state = WorkState.RUNNING,
                        createdAt = 0L,
                        updatedAt = 0L,
                        attempts = 0,
                    ),
                )
        }

    private fun scene(session: FakeLibrarySession) =
        hostedScene(WIDTH, HEIGHT) {
            NpnTheme(
                darkTheme = false,
            ) { QueueScreen(session, onOpenDoc = {}, onOpenEditRun = {}, onOpenCloudSettings = {}) }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
