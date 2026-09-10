package dev.npn.app.ui.detail

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DetailLoadStateTest {
    @Test
    fun nullCountIsAFailureNotAZero() {
        // The content facade returns null for an unreadable/corrupt file or a missing row; that is
        // a failure the viewer offers to retry, never a silent zero.
        pageCountStateOf(null) shouldBe PageCountState.Failed
    }

    @Test
    fun zeroOrNegativeCountIsEmpty() {
        pageCountStateOf(0) shouldBe PageCountState.Empty
        pageCountStateOf(-1) shouldBe PageCountState.Empty
    }

    @Test
    fun positiveCountIsReady() {
        pageCountStateOf(5) shouldBe PageCountState.Ready(5)
    }

    @Test
    fun countAccessorReadsThroughReadyAndDefaultsToZero() {
        PageCountState.Ready(9).count shouldBe 9
        PageCountState.Loading.count shouldBe 0
        PageCountState.Empty.count shouldBe 0
        PageCountState.Failed.count shouldBe 0
    }
}
