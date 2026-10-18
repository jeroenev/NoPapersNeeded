package dev.npn.core.app

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.journalObjectEvents
import dev.npn.core.journal.journalObjects
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A library an earlier build relinked in, with no copies: its relink target showed its original's
 * metadata by inheritance. The first open with this build writes that metadata onto the target as
 * its own, once.
 */
class RelinkCopiesUpgradeTest {
    private val dirs = LibraryDirs()
    private val library = dirs.library
    private val data = dirs.data

    @AfterTest
    fun teardown() = dirs.close()

    @Test
    fun aRelinkWithoutCopiesIsUpgradedOnceAtOpen() =
        runTest {
            Files.write(library.resolve("bill.pdf"), PdfFixtures.embeddedTextPdf("baked"))
            val fs = JvmLibraryFileSystem(library, PathExternalIo)
            val baked = fs.sha256("bill.pdf")
            val original = "a".repeat(64)
            val clock = MutableClock(1_000L)
            val earlierBuild = ContentAddressedJournalWriter(fs, "earlier-build", HlcWatermarkAllocator(clock))
            earlierBuild.commit(
                listOf(
                    PendingOp.fieldSet(original, FieldKeys.TITLE, "Water bill"),
                    PendingOp.fieldSet(original, FieldKeys.CORRESPONDENT, "Aquafin"),
                    PendingOp.tagAdd(original, "filed"),
                    PendingOp.propSet(original, SystemProperty.REVIEWED, "true"),
                    PendingOp.propSet(original, SystemProperty.STORAGE_PATH, "bill.pdf"),
                ),
            )
            clock.advance()
            earlierBuild.commit(listOf(PendingOp.relink(original, baked)))
            clock.advance()
            // The target's own edit after the relink, newer than what it inherited: it wins.
            earlierBuild.commit(listOf(PendingOp.fieldSet(baked, FieldKeys.CORRESPONDENT, "Aquafin NV")))

            openSession(backgroundScope, library, data).use { session, deps ->
                session.rescan()
                val row =
                    deps.db
                        .documentDao()
                        .getByDocId(baked)
                        .shouldNotBeNull()
                row.title shouldBe "Water bill"
                row.correspondent shouldBe "Aquafin NV"
                row.reviewed shouldBe true
                row.relPath shouldBe "bill.pdf"
                deps.db.tagDao().tagsForDocument(baked) shouldContainExactlyInAnyOrder listOf("filed")
                val settings = fs.journalObjectEvents().mapNotNull { it.op as? JournalOp.SettingSet }
                settings.filter { it.key == LibrarySettingKeys.RELINK_COPIES }.map { it.value } shouldBe
                    listOf(RelinkInheritanceUpgrade.DONE)
            }

            // Once: the next open, and another device's first, write nothing more.
            val objects = fs.journalObjects().size
            openSession(backgroundScope, library, data).use { session, _ -> session.rescan() }
            openSession(backgroundScope, library, File(data, "other-device")).use { session, deps ->
                session.rescan()
                deps.db
                    .documentDao()
                    .getByDocId(baked)
                    .shouldNotBeNull()
                    .title shouldBe "Water bill"
            }
            fs.journalObjects().size shouldBe objects
        }
}
