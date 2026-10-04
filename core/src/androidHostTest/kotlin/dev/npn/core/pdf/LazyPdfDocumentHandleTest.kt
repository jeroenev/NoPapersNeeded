package dev.npn.core.pdf

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The Android handle opens each backend only for the calls that need it: drawing a page never
 * parses the document, and counting pages or reading text never stages the renderer's copy.
 */
class LazyPdfDocumentHandleTest {
    private var textOpens = 0
    private var pageOpens = 0
    private val closed = ArrayList<String>()

    private val text =
        object : PdfTextBackend {
            override val pageCount = PAGES

            override fun extractText(pageIndex: Int) = "text of $pageIndex"

            override fun close() {
                closed += "text"
            }
        }

    private val pages =
        object : PdfPageBackend {
            override val pageCount = PAGES

            override fun renderPagePng(
                pageIndex: Int,
                maxDimensionPx: Int,
            ) = byteArrayOf(pageIndex.toByte())

            override fun close() {
                closed += "pages"
            }
        }

    private fun handle() =
        LazyPdfDocumentHandle(
            openText = {
                textOpens++
                text
            },
            openPages = {
                pageOpens++
                pages
            },
        )

    @Test
    fun drawingAPageAndThenCountingNeverParsesTheDocument() {
        handle().use { handle ->
            handle.renderPagePngOrNull(1, 100)?.single() shouldBe 1
            handle.pageCount shouldBe PAGES
            handle.renderPagePngOrNull(PAGES, 100) shouldBe null
        }

        textOpens shouldBe 0
        pageOpens shouldBe 1
        closed shouldBe listOf("pages")
    }

    @Test
    fun countingPagesNeverStagesACopyForTheRenderer() {
        handle().use { handle -> handle.pageCount shouldBe PAGES }

        pageOpens shouldBe 0
        textOpens shouldBe 1
        closed shouldBe listOf("text")
    }

    @Test
    fun readingTextAndDrawingAPageOpenEachBackendOnce() {
        handle().use { handle ->
            repeat(PAGES) { i ->
                handle.extractText(i) shouldBe "text of $i"
                handle.renderPagePng(i, 100).single() shouldBe i.toByte()
            }
            handle.pageCount shouldBe PAGES
        }

        textOpens shouldBe 1
        pageOpens shouldBe 1
        closed shouldBe listOf("pages", "text")
    }

    @Test
    fun aBackendThatCannotOpenFailsTheCallThatNeededIt() {
        val handle =
            LazyPdfDocumentHandle(
                openText = { throw PdfOpenException("Failed to load PDF") },
                openPages = { pages },
            )

        handle.use {
            assertFailsWith<PdfOpenException> { it.extractText(0) }
            it.renderPagePng(0, 100).single() shouldBe 0
        }
    }

    @Test
    fun aClosedHandleOpensNothingMore() {
        val handle = handle()
        handle.close()

        assertFailsWith<IllegalStateException> { handle.pageCount }
        textOpens shouldBe 0
        pageOpens shouldBe 0
    }

    private companion object {
        const val PAGES = 3
    }
}
