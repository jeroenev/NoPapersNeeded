package dev.npn.app.platform

import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A shared copy is written into the share directory itself, whatever name it was handed. */
class ShareFileTest {
    private val cache: File = Files.createTempDirectory("npn-share").toFile()
    private val share = File(cache, "share").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        cache.deleteRecursively()
    }

    @Test
    fun aNameWithFoldersStaysInTheShareDirectory() {
        shareFile(share, "Invoices/October.pdf") shouldBe File(share, "Invoices_October.pdf")
        shareFile(share, "../cached.pdf").parentFile shouldBe share
        shareFile(share, "/abs.pdf").parentFile shouldBe share
    }

    @Test
    fun aPlainNameIsKept() {
        shareFile(share, "Invoice no. 12.pdf") shouldBe File(share, "Invoice no. 12.pdf")
    }

    @Test
    fun aNameThatNamesNoFileIsRefused() {
        assertFailsWith<IllegalArgumentException> { shareFile(share, "") }
    }
}
