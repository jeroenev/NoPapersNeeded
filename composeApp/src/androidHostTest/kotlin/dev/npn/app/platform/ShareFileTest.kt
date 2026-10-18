package dev.npn.app.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * A shared copy is written into a directory of its own in the share directory, whatever name it was
 * handed, and copies older than a day are deleted on the next share.
 */
class ShareFileTest {
    private val cache: File = Files.createTempDirectory("npn-share").toFile()
    private val share = File(cache, "share").apply { mkdirs() }
    private val now = System.currentTimeMillis()

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

    @Test
    fun twoSharesWithOneNameGetTwoFilesAndTheFirstKeepsItsBytes() {
        val first = writeShareCopy(share, "Invoice.pdf", "first".encodeToByteArray(), now)
        val second = writeShareCopy(share, "Invoice.pdf", "second".encodeToByteArray(), now)

        second shouldNotBe first
        first.name shouldBe "Invoice.pdf"
        second.name shouldBe "Invoice.pdf"
        first.parentFile?.parentFile shouldBe share
        second.parentFile?.parentFile shouldBe share
        first.readBytes().decodeToString() shouldBe "first"
        second.readBytes().decodeToString() shouldBe "second"
    }

    @Test
    fun aShareOlderThanADayIsDeletedAndARecentOneKept() {
        val old = writeShareCopy(share, "Old.pdf", byteArrayOf(1), now).ageBy(2.days)
        val recent = writeShareCopy(share, "Recent.pdf", byteArrayOf(2), now).ageBy(1.hours)
        val legacy = File(share, "Flat.pdf").apply { writeBytes(byteArrayOf(3)) }.ageBy(2.days)

        val next = writeShareCopy(share, "Next.pdf", byteArrayOf(4), now)

        old.exists() shouldBe false
        legacy.exists() shouldBe false
        recent.readBytes() shouldBe byteArrayOf(2)
        next.readBytes() shouldBe byteArrayOf(4)
    }

    /** Dates this share's copy (and the directory it was written to) [age] before [now]. */
    private fun File.ageBy(age: Duration): File =
        apply {
            setLastModified(now - age.inWholeMilliseconds)
            parentFile?.takeIf { it != share }?.setLastModified(now - age.inWholeMilliseconds)
        }
}
