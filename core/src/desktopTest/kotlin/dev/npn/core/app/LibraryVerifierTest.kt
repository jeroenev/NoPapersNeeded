package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPaths
import dev.npn.core.sim.FsMethod
import dev.npn.core.sim.InMemoryLibraryFileSystem
import dev.npn.core.sim.SimFaults
import dev.npn.core.sync.JournalDocView
import dev.npn.core.util.EpochClock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Verify library: what it finds, in both the document folder and the journal object store. */
class LibraryVerifierTest {
    private class Fixture(
        val fs: InMemoryLibraryFileSystem,
    ) {
        val verifier =
            LibraryVerifier(
                fs,
                EpochClock { 1_700_000_000_000L },
            )
    }

    private fun fixture(faults: SimFaults = SimFaults()): Fixture =
        Fixture(InMemoryLibraryFileSystem(now = { 1_700_000_000_000L }, faults = faults))

    private fun row(
        bytes: ByteArray,
        relPath: String = "Invoice.pdf",
        size: Long = bytes.size.toLong(),
        docId: String = sha256Bytes(bytes),
    ) = DocumentEntity(
        docId = docId,
        relPath = relPath,
        fileName = NpnPaths.fileName(relPath),
        size = size,
        mtime = 1_700_000_000_000L,
    )

    private fun objectPath(bytes: ByteArray): String = JournalPaths.objectPath(sha256Bytes(bytes))

    private suspend fun LibraryVerifier.run(
        rows: List<DocumentEntity>,
        sources: List<String> = emptyList(),
        views: Map<String, JournalDocView> = emptyMap(),
        onProgress: (LibraryVerificationProgress) -> Unit = {},
    ): LibraryVerificationReport = verify(rows, sources, { views }, onProgress)

    @Test
    fun aCleanLibraryHasNoProblems() =
        runTest {
            val f = fixture()
            val bytes = "a clean document".encodeToByteArray()
            f.fs.writeBytesOnce("Invoice.pdf", bytes)
            val objectBytes = "{}".encodeToByteArray()
            f.fs.writeBytesOnce(objectPath(objectBytes), objectBytes)

            val report = f.verifier.run(rows = listOf(row(bytes)), sources = listOf(objectPath(objectBytes)))

            report.ok shouldBe true
            report.documentsChecked shouldBe 1
            report.objectsChecked shouldBe 1
            report.problems.shouldBeEmpty()
            report.notes.shouldBeEmpty()
        }

    @Test
    fun aMissingDocumentFileIsReportedWithItsName() =
        runTest {
            val f = fixture()
            val bytes = "never written".encodeToByteArray()

            val report = f.verifier.run(listOf(row(bytes)))

            report.documentProblems.single().kind shouldBe VerificationProblemKind.DOCUMENT_MISSING
            report.documentProblems.single().docId shouldBe sha256Bytes(bytes)
            report.documentProblems.single().fileName shouldBe "Invoice.pdf"
        }

    @Test
    fun aFileOfTheWrongSizeIsASizeMismatchAndIsNotHashed() =
        runTest {
            val f = fixture()
            val bytes = "abcdef".encodeToByteArray()
            f.fs.writeBytesOnce("Invoice.pdf", bytes)
            val faults = f.fs.faults
            // Any hash would be a second finding; the size already proves the file is not the document's.
            faults.arm(SimFaults.Rule(FsMethod.SHA256, "Invoice.pdf", SimFaults.Point.BEFORE, 1))

            val report = f.verifier.run(listOf(row(bytes, size = bytes.size + 5L)))

            val problem = report.documentProblems.single()
            problem.kind shouldBe VerificationProblemKind.DOCUMENT_SIZE_MISMATCH
            problem.expectedSize shouldBe bytes.size + 5L
            problem.actualSize shouldBe bytes.size.toLong()
        }

    @Test
    fun bytesThatAreNotTheDocumentsAreAHashMismatch() =
        runTest {
            val f = fixture()
            val expected = "the document's bytes".encodeToByteArray()
            val other = "someone else's".encodeToByteArray()
            f.fs.writeBytesOnce("Invoice.pdf", other)

            val report = f.verifier.run(listOf(row(expected, size = other.size.toLong())))

            val problem = report.documentProblems.single()
            problem.kind shouldBe VerificationProblemKind.DOCUMENT_HASH_MISMATCH
            problem.expectedHash shouldBe sha256Bytes(expected)
            problem.actualHash shouldBe sha256Bytes(other)
        }

    @Test
    fun anUnreadableDocumentIsReported() =
        runTest {
            val f = fixture()
            val bytes = "cannot be read".encodeToByteArray()
            f.fs.writeBytesOnce("Invoice.pdf", bytes)
            f.fs.faults.arm(SimFaults.Rule(FsMethod.SHA256, "Invoice.pdf", SimFaults.Point.BEFORE, 1))

            val report = f.verifier.run(listOf(row(bytes)))

            report.documentProblems.single().kind shouldBe VerificationProblemKind.DOCUMENT_UNREADABLE
        }

    @Test
    fun aTamperedJournalObjectIsAHashMismatch() =
        runTest {
            val f = fixture()
            val expected = "{\"event\":1}".encodeToByteArray()
            val tampered = "{\"event\":2}".encodeToByteArray()
            val path = objectPath(expected)
            f.fs.writeBytesOnce(path, tampered)

            val report = f.verifier.run(rows = emptyList(), sources = listOf(path))

            val problem = report.objectProblems.single()
            problem.kind shouldBe VerificationProblemKind.OBJECT_HASH_MISMATCH
            problem.relPath shouldBe path
            problem.expectedHash shouldBe sha256Bytes(expected)
            problem.actualHash shouldBe sha256Bytes(tampered)
        }

    @Test
    fun aFileTheJournalCannotReadAsAnObjectIsOrphaned() =
        runTest {
            val f = fixture()
            f.fs.writeBytesOnce(".npn/journal/objects-v2-aa/not-an-object.jsonl", "{}".encodeToByteArray())

            val report = f.verifier.run(rows = emptyList())

            report.objectProblems.single().kind shouldBe VerificationProblemKind.OBJECT_ORPHANED
        }

    @Test
    fun conflictCopiesAreHashChecked() =
        runTest {
            val f = fixture()
            val bytes = "{}".encodeToByteArray()
            val canonical = objectPath(bytes)
            val copy = canonical.removeSuffix(".jsonl") + " (conflicted copy laptop).jsonl"
            f.fs.writeBytesOnce(copy, "bad".encodeToByteArray())

            val report = f.verifier.run(rows = emptyList())

            report.objectProblems.single().kind shouldBe VerificationProblemKind.OBJECT_HASH_MISMATCH
            report.objectsChecked shouldBe 1
        }

    @Test
    fun anObjectTheIndexAppliedButTheStoreLostIsMissing() =
        runTest {
            val f = fixture()
            val lost = "b".repeat(64)

            val report = f.verifier.run(rows = emptyList(), sources = listOf(JournalPaths.objectPath(lost)))

            val problem = report.objectProblems.single()
            problem.kind shouldBe VerificationProblemKind.OBJECT_MISSING
            problem.relPath shouldBe JournalPaths.objectPath(lost)
        }

    @Test
    fun aSupersededDocumentStillOnDiskIsANoteNotAProblem() =
        runTest {
            val f = fixture()
            val bytes = "the old version".encodeToByteArray()
            f.fs.writeBytesOnce("Old.pdf", bytes)
            val row = row(bytes, relPath = "Old.pdf")

            val report =
                f.verifier.run(
                    rows = listOf(row),
                    views =
                        mapOf(
                            row.docId to
                                JournalDocView(
                                    intendedPath = null,
                                    deleted = true,
                                    trashed = false,
                                    supersededBy = "new",
                                ),
                        ),
                )

            report.problems.shouldBeEmpty()
            report.notes.single().kind shouldBe VerificationNoteKind.SUPERSEDED_DOCUMENT
            report.notes.single().docId shouldBe row.docId
        }

    @Test
    fun aFailedJournalWalkIsReportedOnceAndNotAsThousandsOfMissingObjects() =
        runTest {
            val f = fixture()
            val source = JournalPaths.objectPath("c".repeat(64))
            f.fs.faults.arm(SimFaults.Rule(FsMethod.LIST_UNDER, NpnPaths.JOURNAL_DIR, SimFaults.Point.BEFORE, 1))

            val report = f.verifier.run(rows = emptyList(), sources = listOf(source))

            report.storeProblems.single().kind shouldBe VerificationProblemKind.STORE_UNREADABLE
            report.objectProblems.shouldBeEmpty()
            report.objectsChecked shouldBe 0
        }

    @Test
    fun aFolderAtADocumentsPathIsNotAFileNotASizeMismatch() =
        runTest {
            val f = fixture()
            val bytes = "a document".encodeToByteArray()
            f.fs.writeBytesOnce("Contracts/Lease.pdf", bytes)
            val row = row(bytes, relPath = "Contracts")

            val report = f.verifier.run(listOf(row))

            report.documentProblems.single().kind shouldBe VerificationProblemKind.DOCUMENT_NOT_A_FILE
        }

    @Test
    fun anUnreadableJournalObjectIsReported() =
        runTest {
            val f = fixture()
            val bytes = "{}".encodeToByteArray()
            val path = objectPath(bytes)
            f.fs.writeBytesOnce(path, bytes)
            f.fs.faults.arm(SimFaults.Rule(FsMethod.SHA256, path, SimFaults.Point.BEFORE, 1))

            val report = f.verifier.run(rows = emptyList())

            report.objectProblems.single().kind shouldBe VerificationProblemKind.OBJECT_UNREADABLE
            report.objectsChecked shouldBe 1
        }

    @Test
    fun theAppsOwnStagingFileIsNotAnOrphan() =
        runTest {
            val f = fixture()
            val hash = "d".repeat(64)
            f.fs.writeBytesOnce(JournalPaths.stagingPath(hash, "install", "nonce"), "{}".encodeToByteArray())

            val report = f.verifier.run(rows = emptyList())

            report.problems.shouldBeEmpty()
            report.notes.shouldBeEmpty()
        }

    @Test
    fun anObjectStoreOfAnotherVersionIsOrphaned() =
        runTest {
            val f = fixture()
            val hash = "e".repeat(64)
            f.fs.writeBytesOnce(".npn/journal/objects-v3-${hash.take(2)}/$hash.jsonl", "{}".encodeToByteArray())

            val report = f.verifier.run(rows = emptyList())

            report.objectProblems.single().kind shouldBe VerificationProblemKind.OBJECT_ORPHANED
            report.objectsChecked shouldBe 1
        }

    @Test
    fun progressWalksBothPhasesAndEndsAtTheirTotals() =
        runTest {
            val f = fixture()
            val bytes = "a document".encodeToByteArray()
            f.fs.writeBytesOnce("Invoice.pdf", bytes)
            val objectBytes = "{}".encodeToByteArray()
            f.fs.writeBytesOnce(objectPath(objectBytes), objectBytes)

            val progress = mutableListOf<LibraryVerificationProgress>()
            f.verifier.run(listOf(row(bytes)), onProgress = progress::add)

            progress.first() shouldBe LibraryVerificationProgress(VerificationPhase.DOCUMENTS, 0, 1)
            progress.last { it.phase == VerificationPhase.DOCUMENTS }.done shouldBe 1
            progress.last { it.phase == VerificationPhase.OBJECTS } shouldBe
                LibraryVerificationProgress(VerificationPhase.OBJECTS, 1, 1)
        }

    @Test
    fun cancellationStopsTheWalk() =
        runTest {
            val f = fixture()
            f.fs.writeBytesOnce("Invoice.pdf", "a document".encodeToByteArray())

            assertFailsWith<CancellationException> {
                f.verifier.run(rows = listOf(row("a document".encodeToByteArray()))) {
                    throw CancellationException("stop")
                }
            }
        }

    @Test
    fun aFailedDocumentWalkIsReportedOnceAndTheObjectStoreIsStillChecked() =
        runTest {
            val f = fixture()
            val bytes = "{}".encodeToByteArray()
            f.fs.writeBytesOnce(objectPath(bytes), bytes)
            f.fs.faults.arm(SimFaults.Rule(FsMethod.LIST_INDEXABLE, null, SimFaults.Point.BEFORE, 1))

            val report = f.verifier.run(rows = listOf(row("a document".encodeToByteArray())))

            report.problems
                .map { it.kind } shouldContainExactly
                listOf(VerificationProblemKind.STORE_UNREADABLE)
            report.objectsChecked shouldBe 1
        }
}
