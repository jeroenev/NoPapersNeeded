package dev.npn.app

import dev.npn.app.ui.detail.BrowseContext
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class BrowseContextTest {
    private fun ctx(vararg ids: String) = BrowseContext().apply { setOrder(ids.toList()) }

    @Test
    fun neighbourWalksListAndStopsAtEnds() {
        val c = ctx("a", "b", "c")
        c.neighbour("a", -1) shouldBe null
        c.neighbour("a", 1) shouldBe "b"
        c.neighbour("b", -1) shouldBe "a"
        c.neighbour("b", 1) shouldBe "c"
        c.neighbour("c", 1) shouldBe null
    }

    @Test
    fun neighbourOfUnknownDocIsNull() {
        ctx("a", "b").neighbour("z", 1) shouldBe null
    }

    @Test
    fun positionIsOneBasedWithTotal() {
        val c = ctx("a", "b", "c")
        c.positionOf("a") shouldBe (1 to 3)
        c.positionOf("c") shouldBe (3 to 3)
        c.positionOf("z") shouldBe null
    }

    @Test
    fun setOrderReplacesPreviousOrder() {
        val c = ctx("a", "b")
        c.setOrder(listOf("x", "y", "z"))
        c.positionOf("y") shouldBe (2 to 3)
        c.neighbour("a", 1) shouldBe null
    }
}
