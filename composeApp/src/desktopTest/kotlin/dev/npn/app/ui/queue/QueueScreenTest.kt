@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.queue

import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.WorkState
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailCheckReport
import dev.npn.core.mail.MailFailureReason
import dev.npn.core.mail.MailProgress
import dev.npn.core.mail.MailState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Activity: the background bulk edits, problems first, and the cloud sync and email rows. */
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

    /** A failing email check is a problem, with the way to where it is fixed. */
    @Test
    fun aFailedEmailCheckIsAProblemThatLeadsToEmailSettings() =
        onUiThread {
            val session =
                withMail(
                    MailState(
                        account = configured,
                        lastCheckedAtMillis = 1L,
                        lastError = MailFailureReason.NO_PASSWORD,
                    ),
                )
            scene(session).closing {
                render()
                has { it.text() == "Needs attention" } shouldBe true
                has { it.text() == "Could not check email: no password is saved" } shouldBe true
                click { it.text() == "Email settings" }
                render()
                openedEmailSettings shouldBe 1
            }
        }

    /** A running check shows as one, with Cancel instead of Check now. */
    @Test
    fun aRunningEmailCheckOffersCancel() =
        onUiThread {
            val session =
                withMail(
                    MailState(
                        account = configured,
                        checking = true,
                        progress = MailProgress(messagesSeen = 3, remaining = 7),
                    ),
                )
            scene(session).closing {
                render()
                has { it.text() == "Checking email…" } shouldBe true
                has { it.text() == "About 7 messages left" } shouldBe true
                has { it.text() == "Check now" } shouldBe false
                click { it.text() == "Cancel" }
                render()
                session.mail.cancels shouldBe 1
            }
        }

    /** After a check, the row says when it ran and what it imported, with its own Check now. */
    @Test
    fun aFinishedEmailCheckSaysWhatItImported() =
        onUiThread {
            val session =
                withMail(
                    MailState(
                        account = configured,
                        lastCheckedAtMillis = 1L,
                        lastReport = MailCheckReport(imported = 2),
                    ),
                )
            scene(session).closing {
                render()
                has { it.text()?.endsWith("· 2 documents imported") == true } shouldBe true
                click { it.text() == "Check now" }
                render()
                session.mail.checks shouldBe 1
            }
        }

    private val configured = MailAccount(host = "mail.example.com", username = "someone")

    private var openedEmailSettings = 0

    private fun withMail(state: MailState) = FakeLibrarySession().apply { mail.state.value = state }

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
            ) {
                QueueScreen(
                    session,
                    settings = FakeSettingsRepository(),
                    onOpenDoc = {},
                    onOpenEditRun = {},
                    onOpenCloudSettings = {},
                    onOpenOcrSettings = {},
                    onOpenAiSettings = {},
                    onOpenEmailSettings = { openedEmailSettings++ },
                )
            }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
