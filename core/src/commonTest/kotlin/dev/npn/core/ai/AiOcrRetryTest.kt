package dev.npn.core.ai

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The page-transcription retry policy ([transcribeWithRetry]). This is the piece that decides how
 * many paid calls one unreadable page costs, so the count matters as much as the result.
 */
class AiOcrRetryTest {
    @Test
    fun firstUsableAnswerStopsTheRetryLoop() =
        runTest {
            var calls = 0
            val text =
                transcribeWithRetry(attempts = 2) {
                    calls++
                    "Invoice 42"
                }

            text shouldBe "Invoice 42"
            calls shouldBe 1
        }

    @Test
    fun anExplicitlyBlankPageIsAnAnswerAndIsNotRetried() =
        runTest {
            // "" is the model reporting a page with no text (AiText.parseTranscription), not a
            // failed call. Asking again would only buy the same answer at the same price.
            var calls = 0
            val text =
                transcribeWithRetry(attempts = 2) {
                    calls++
                    ""
                }

            text shouldBe ""
            calls shouldBe 1
        }

    @Test
    fun anUnusableAnswerIsRetriedOnceAndThenAcceptedAsNoText() =
        runTest {
            var calls = 0
            val text =
                transcribeWithRetry(attempts = 2) {
                    calls++
                    null
                }

            text shouldBe ""
            calls shouldBe 2
        }

    @Test
    fun aRetryThatSucceedsRecoversThePage() =
        runTest {
            var calls = 0
            val text =
                transcribeWithRetry(attempts = 2) {
                    calls++
                    if (calls == 1) null else "second time lucky"
                }

            text shouldBe "second time lucky"
            calls shouldBe 2
        }

    @Test
    fun aRetryAfterATransientEndpointErrorRecoversThePage() =
        runTest {
            var calls = 0
            val text =
                transcribeWithRetry(attempts = 2) {
                    calls++
                    if (calls == 1) error("502 from the endpoint") else "read on the retry"
                }

            text shouldBe "read on the retry"
            calls shouldBe 2
        }

    @Test
    fun everyAttemptFailingRethrowsRatherThanReportingAnEmptyPage() =
        runTest {
            // A page whose text is unknown must fail its document's run. Returning "" here would
            // publish a write-once sidecar with a silent hole in it.
            var calls = 0
            val failure =
                assertFailsWith<IllegalStateException> {
                    transcribeWithRetry(attempts = 2) {
                        calls++
                        error("no vision support on this model")
                    }
                }

            failure.message shouldBe "no vision support on this model"
            calls shouldBe 2
        }

    @Test
    fun anErrorFollowedByAnUnusableAnswerStillFails() =
        runTest {
            // Mixed outcomes: something did go wrong, so the run hears about it rather than
            // recording the page as blank.
            var calls = 0
            assertFailsWith<IllegalStateException> {
                transcribeWithRetry(attempts = 2) {
                    calls++
                    if (calls == 1) error("connection reset") else null
                }
            }

            calls shouldBe 2
        }

    @Test
    fun cancellationLeavesImmediatelyInsteadOfSpendingAnotherCall() =
        runTest {
            // The caller is gone; a second call would be billed for a result nobody reads.
            var calls = 0
            assertFailsWith<CancellationException> {
                transcribeWithRetry(attempts = 2) {
                    calls++
                    throw CancellationException("session closed")
                }
            }

            calls shouldBe 1
        }
}
