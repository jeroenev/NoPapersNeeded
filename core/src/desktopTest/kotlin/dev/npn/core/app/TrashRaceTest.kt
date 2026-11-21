package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.indexTest
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * A trash checks the document's file, then moves it: another process can replace the file in
 * between, and the lock the trash holds does not stop it. Only the document's own bytes go into
 * the trash.
 */
class TrashRaceTest {
    /**
     * The file is replaced right after the trash checked it: the replacement is put back where it
     * was, for the next rescan, and the document is trashed as one whose file is gone.
     */
    @Test
    fun aFileReplacedAfterTheCheckIsPutBackAndTheDocumentStillGoes() =
        indexTest { rig ->
            rig.writePdf("a.pdf", "alpha")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            var replaced = false
            // Its first look at the file is the trash's check: the other process writes right after.
            val racing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun stat(relPath: String): FileMeta? =
                        rig.fs.stat(relPath).also {
                            if (relPath == "a.pdf" && !replaced) {
                                replaced = true
                                rig.writePdf("a.pdf", "another process's file")
                            }
                        }
                }

            rig.docLifecycle(coordinator, files = racing).trashDoc(id).shouldNotBeNull()

            replaced.shouldBeTrue()
            rig.fs.readBytes("a.pdf").decodeToString() shouldBe "another process's file"
            rig.fs.trashEntries().shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /** Untouched, the document's own file goes into the trash, as always. */
    @Test
    fun anUntouchedFileGoesIntoTheTrash() =
        indexTest { rig ->
            rig.writePdf("a.pdf", "alpha")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")

            rig.docLifecycle(coordinator).trashDoc(id).shouldNotBeNull()

            rig.exists("a.pdf") shouldBe false
            rig.fs.trashEntries() shouldHaveSize 1
        }
}
