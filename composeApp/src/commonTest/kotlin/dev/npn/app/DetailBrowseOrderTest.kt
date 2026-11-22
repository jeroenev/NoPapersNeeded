package dev.npn.app

import dev.npn.app.ui.detail.DetailBrowseOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DetailBrowseOrderTest {
    @Test
    fun startsOnTheOpenedDocumentAndWalksTheOrder() {
        val order = DetailBrowseOrder(listOf("a", "b", "c"), "b")
        order.initialPage shouldBe 1
        order.idAt(0) shouldBe "a"
        order.idAt(2) shouldBe "c"
        order.hasPrevious(0) shouldBe false
        order.hasPrevious(1) shouldBe true
        order.hasNext(1) shouldBe true
        order.hasNext(2) shouldBe false
    }

    @Test
    fun aDocumentOutsideTheOrderIsItsOwnSinglePage() {
        val order = DetailBrowseOrder.of(listOf("a", "b"), "elsewhere")
        order.ids shouldBe listOf("elsewhere")
        order.initialPage shouldBe 0
        order.hasPrevious(0) shouldBe false
        order.hasNext(0) shouldBe false
    }

    @Test
    fun theLibraryOrderIsKeptWhenItHoldsTheDocument() {
        val order = DetailBrowseOrder.of(listOf("a", "b", "c"), "c")
        order.ids shouldBe listOf("a", "b", "c")
        order.initialPage shouldBe 2
    }

    @Test
    fun anEmptyOrderFallsBackToTheDocument() {
        DetailBrowseOrder.of(emptyList(), "a").ids shouldBe listOf("a")
    }
}
