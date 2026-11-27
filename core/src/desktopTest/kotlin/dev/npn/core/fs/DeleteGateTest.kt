package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/** The app deletes a copy of a document's bytes only by a fact it holds, and only while the file still holds them. */
class DeleteGateTest {
    private val root: Path = Files.createTempDirectory("npn-delete-gate-test")
    private val fs = JvmLibraryFileSystem(root, PathExternalIo)
    private val gate = DeleteGate(fs)
    private val bytes = "the document".encodeToByteArray()
    private val docId = sha256Bytes(bytes)

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    /** Each journal fact lets the delete it is for, and only while the journal has it. */
    @Test
    fun aJournalFactLetsTheDeleteOnlyWhileTheJournalHasIt() =
        runTest {
            for (fact in listOf("deleted", "trashed", "superseded")) {
                val facts = factsOf(fact)
                val authorities =
                    mapOf(
                        "deleted" to DeleteAuthority.Tombstone(facts),
                        "trashed" to DeleteAuthority.Trash(facts),
                        "superseded" to DeleteAuthority.Superseded(facts),
                    )
                for ((needs, authority) in authorities) {
                    fs.writeBytesOnce("a.pdf", bytes)
                    val went = gate.reap("a.pdf", docId, authority)
                    // A deleted document's trash entries go by the purge too.
                    val allowed = needs == fact || (needs == "trashed" && fact == "deleted")
                    went shouldBe allowed
                    fs.exists("a.pdf") shouldBe !allowed
                    if (fs.exists("a.pdf")) fs.delete("a.pdf")
                }
            }
            gate.reap("a.pdf", docId, DeleteAuthority.Tombstone(DocumentFacts.None)).shouldBeFalse()
        }

    /** An extra copy goes while another file holds the same bytes, never the copy it is told to keep. */
    @Test
    fun anExtraCopyGoesOnlyBesideAnotherFileHoldingItsBytes() =
        runTest {
            fs.writeBytesOnce("a.pdf", bytes)
            fs.writeBytesOnce("b.pdf", "other bytes".encodeToByteArray())

            gate.reap("a.pdf", docId, DeleteAuthority.ExtraCopy("b.pdf")).shouldBeFalse()
            gate.reap("a.pdf", docId, DeleteAuthority.ExtraCopy("a.pdf")).shouldBeFalse()
            fs.exists("a.pdf").shouldBeTrue()

            fs.writeBytesOnce("c.pdf", bytes)
            gate.reap("a.pdf", docId, DeleteAuthority.ExtraCopy("c.pdf")).shouldBeTrue()
            fs.exists("a.pdf").shouldBeFalse()
            fs.exists("c.pdf").shouldBeTrue()
        }

    /**
     * A file written over since it was proved goes nowhere, whatever the fact: its stat moved, so it
     * is hashed again, and holds other bytes. One whose stat is unchanged is not hashed again.
     */
    @Test
    fun aFileWrittenOverSinceItWasProvedStays() =
        runTest {
            val deleted = factsOf("deleted")
            fs.writeBytesOnce("a.pdf", bytes)
            val proved = checkNotNull(fs.stat("a.pdf"))
            Files.write(root.resolve("a.pdf"), "written over".encodeToByteArray())
            Files.setLastModifiedTime(root.resolve("a.pdf"), FileTime.fromMillis(proved.mtimeMillis + 60_000))

            gate.reap("a.pdf", docId, DeleteAuthority.Tombstone(deleted), proved).shouldBeFalse()
            fs.exists("a.pdf").shouldBeTrue()
            gate.reap("gone.pdf", docId, DeleteAuthority.Tombstone(deleted)).shouldBeFalse()

            fs.writeBytesOnce("b.pdf", bytes)
            gate.reap("b.pdf", docId, DeleteAuthority.Tombstone(deleted), checkNotNull(fs.stat("b.pdf"))).shouldBeTrue()
        }

    /** A journal with [fact] ("deleted", "trashed" or "superseded") of [docId], and no other. */
    private fun factsOf(fact: String): DocumentFacts =
        object : DocumentFacts {
            override suspend fun isDeleted(docId: String) = fact == "deleted"

            override suspend fun isTrashed(docId: String) = fact == "trashed"

            override suspend fun successorOf(docId: String) = if (fact == "superseded") "b".repeat(64) else docId
        }
}
