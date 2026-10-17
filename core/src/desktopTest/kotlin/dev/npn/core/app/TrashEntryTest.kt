package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A trash entry is a document's copy only while it holds the document's bytes. The hash prefix in its
 * name narrows the search, but another document's hash can share it, and an older build could file
 * another file under a document's name.
 */
class TrashEntryTest {
    /** A trashed document: its id, and the path it was trashed from. */
    private data class Trashed(
        val id: String,
        val path: String,
    )

    private class Library(
        val rig: IndexRig,
    ) {
        val coordinator = rig.coordinator()
        val lifecycle =
            rig.docLifecycle(coordinator)

        /** Write [content] at [path], index it and trash it. */
        suspend fun trash(
            path: String,
            content: String,
        ): Trashed {
            rig.writePdf(path, content)
            coordinator.rescan()
            val id = rig.fs.sha256(path)
            lifecycle.trashDoc(id)
            return Trashed(id, path)
        }

        /** Whether an entry in the trash holds [doc]'s bytes. */
        suspend fun inTrash(doc: Trashed) = rig.fs.trashEntries().any { rig.fs.sha256(it.relPath) == doc.id }

        /** Put another file's bytes in [doc]'s only trash entry, as an older build's trash could. */
        suspend fun misfile(doc: Trashed): String {
            val entry =
                rig.fs
                    .trashEntries()
                    .single()
                    .relPath
            rig.deleteFile(entry)
            rig.writePdf(entry, "another file")
            inTrash(doc).shouldBeFalse()
            return entry
        }
    }

    private suspend fun withLibrary(block: suspend Library.() -> Unit) = IndexRig.create().use { Library(it).block() }

    /**
     * Run [block] over two trashed documents whose trash entries carry the same hash prefix, once in
     * each order on a fresh library: whichever entry the trash lists first, one run acts first on the
     * other document.
     */
    private fun withClashingEntries(block: suspend Library.(first: Trashed, second: Trashed) -> Unit) =
        runTest {
            for (reversed in listOf(false, true)) {
                withLibrary {
                    val docs = CLASHING.mapIndexed { i, content -> trash("doc $i.pdf", content) }
                    docs.map { it.id.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) }.distinct() shouldHaveSize 1
                    val (first, second) = if (reversed) docs.reversed() else docs
                    block(first, second)
                }
            }
        }

    @Test
    fun eachOfTwoDocumentsWhoseEntriesShareAPrefixIsRestoredFromItsOwn() =
        withClashingEntries { first, second ->
            lifecycle.restoreDoc(first.id)

            rig.fs.sha256(first.path) shouldBe first.id
            inTrash(second).shouldBeTrue()

            lifecycle.restoreDoc(second.id)

            rig.fs.sha256(second.path) shouldBe second.id
            rig.fs.trashEntries().shouldBeEmpty()
        }

    @Test
    fun deletingForGoodOneOfTwoDocumentsWhoseEntriesShareAPrefixLeavesTheOthers() =
        withClashingEntries { deleted, kept ->
            lifecycle.deleteDocPermanently(deleted.id)

            inTrash(deleted).shouldBeFalse()
            inTrash(kept).shouldBeTrue()
            lifecycle.restoreDoc(kept.id)
            rig.fs.sha256(kept.path) shouldBe kept.id
        }

    /**
     * The document has no copy in the trash, only an entry under its name holding another file. A
     * restore brings nothing back, as for a document whose trash entry is gone.
     */
    @Test
    fun anEntryHoldingAnotherFileIsNotRestoredAsTheDocument() =
        runTest {
            withLibrary {
                val doc = trash("inbox/invoice.pdf", "the document")
                val entry = misfile(doc)

                lifecycle.restoreDoc(doc.id)

                rig.fs.readBytes(entry).decodeToString() shouldBe "another file"
                rig.exists(doc.path).shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(doc.id)
                    .shouldNotBeNull()
                    .trashed
                    .shouldBeFalse()
            }
        }

    /** Nor is that entry deleted with the document. */
    @Test
    fun anEntryHoldingAnotherFileIsNotDeletedWithTheDocument() =
        runTest {
            withLibrary {
                val doc = trash("inbox/invoice.pdf", "the document")
                val entry = misfile(doc)

                lifecycle.deleteDocPermanently(doc.id)

                rig.fs.readBytes(entry).decodeToString() shouldBe "another file"
                rig.db
                    .documentDao()
                    .getByDocId(doc.id)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
            }
        }

    private companion object {
        /** Two contents whose SHA-256 hashes share their first eight hex characters (`d77f15bd`). */
        val CLASHING = listOf("trashed document 35434", "trashed document 102888")
    }
}
