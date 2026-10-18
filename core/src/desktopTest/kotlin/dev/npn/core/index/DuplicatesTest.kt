package dev.npn.core.index

import dev.npn.core.fs.sha256Bytes
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A recorded duplicate is what one rescan found, and the files can change after it: the rows follow
 * the next rescan, and removing a copy never takes a file that is not an extra copy any more.
 */
class DuplicatesTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun teardown() = rig.close()

    /**
     * The copy was overwritten with another document since the scan that recorded it: removing the
     * "duplicate" must not take that document's only file.
     */
    @Test
    fun removingADuplicateOverwrittenWithAnotherDocumentKeepsThatDocument() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")
            rig.writePdf("inbox/a.pdf", "echo, another document")

            rig.maintenance.resolveDuplicate(doc, "inbox/a.pdf")

            rig.exists("inbox/a.pdf").shouldBeTrue()
            rig.db
                .duplicateDao()
                .forDoc(doc)
                .shouldBeEmpty()
            rig.rescan()
            val other = rig.fs.sha256("inbox/a.pdf")
            rig.db
                .documentDao()
                .getByDocId(other)
                .shouldNotBeNull()
                .missing
                .shouldBeFalse()
        }

    /** A rescan lists the whole library, so a copy it no longer finds loses its duplicates row. */
    @Test
    fun aRescanForgetsADuplicateOverwrittenWithAnotherDocument() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")
            rig.writePdf("inbox/a.pdf", "echo, another document")

            rig.rescan().duplicates.shouldBeEmpty()

            rig.db
                .duplicateDao()
                .forDoc(doc)
                .shouldBeEmpty()
            rig.db
                .documentDao()
                .rowAtPath("inbox/a.pdf")
                .shouldNotBeNull()
                .docId shouldBe rig.fs.sha256("inbox/a.pdf")
        }

    @Test
    fun aRescanForgetsADuplicateThatWasDeleted() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")
            rig.deleteFile("inbox/a.pdf")

            rig.rescan()

            rig.db
                .duplicateDao()
                .forDoc(doc)
                .shouldBeEmpty()
        }

    /**
     * The original went, so the copy is the document's only file now: removing it as a duplicate
     * would lose the document.
     */
    @Test
    fun removingADuplicateWhoseOriginalWentKeepsIt() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")
            rig.deleteFile("a.pdf")

            rig.maintenance.resolveDuplicate(doc, "inbox/a.pdf")

            rig.exists("inbox/a.pdf").shouldBeTrue()
            rig.rescan()
            rig.db
                .documentDao()
                .getByDocId(doc)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/a.pdf"
            rig.db
                .duplicateDao()
                .forDoc(doc)
                .shouldBeEmpty()
        }

    @Test
    fun aRealDuplicateIsRemoved() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")

            rig.maintenance.resolveDuplicate(doc, "inbox/a.pdf")

            rig.exists("inbox/a.pdf").shouldBeFalse()
            rig.exists("a.pdf").shouldBeTrue()
            rig.db
                .duplicateDao()
                .forDoc(doc)
                .shouldBeEmpty()
        }

    /**
     * Removing a copy reads no file under the writer lock, which every write, import and rescan waits
     * on: the copy is hashed before it, and the document's own unchanged file is known by its stat.
     */
    @Test
    fun removingADuplicateHashesNothingUnderTheLockNorTheDocumentsOwnFile() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")
            val watched = WatchedFileSystem(rig.fs).also { it.db = rig.db }
            val hashedUnderLock = ArrayList<String>()
            val maintenance =
                MaintenanceService(watched, rig.db.documentDao(), rig.db.duplicateDao()) { step ->
                    val before = watched.calls("sha256").size
                    step()
                    hashedUnderLock += watched.calls("sha256").drop(before)
                }

            maintenance.resolveDuplicate(doc, "inbox/a.pdf")

            rig.exists("inbox/a.pdf").shouldBeFalse()
            rig.exists("a.pdf").shouldBeTrue()
            watched.calls("sha256") shouldNotContain "sha256 a.pdf"
            hashedUnderLock.shouldBeEmpty()
        }

    /** A copy written over between its hash and the lock is not taken for the copy it was. */
    @Test
    fun aCopyChangedAfterItWasHashedIsNotRemoved() =
        runTest {
            val doc = recordDuplicate("a.pdf", "inbox/a.pdf")
            val edited = "alpha, edited since".encodeToByteArray()
            val maintenance =
                MaintenanceService(rig.fs, rig.db.documentDao(), rig.db.duplicateDao()) { step ->
                    rig.writePdf("inbox/a.pdf", edited.decodeToString())
                    step()
                }

            maintenance.resolveDuplicate(doc, "inbox/a.pdf")

            rig.fs.sha256("inbox/a.pdf") shouldBe sha256Bytes(edited)
        }

    /** Index [original], copy it to [copy] and rescan, so the copy is a recorded duplicate; returns the document. */
    private suspend fun recordDuplicate(
        original: String,
        copy: String,
    ): String {
        rig.writePdf(original, "alpha")
        rig.rescan()
        rig.copyFile(original, copy)
        rig.rescan()
        val doc = rig.fs.sha256(original)
        rig.db
            .duplicateDao()
            .forDoc(doc)
            .map { it.relPath } shouldContainExactly listOf(copy)
        return doc
    }
}
