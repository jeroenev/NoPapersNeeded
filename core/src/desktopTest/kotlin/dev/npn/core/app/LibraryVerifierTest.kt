package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.missingSentinel
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

    /** A document the journal shows, meant to be at [intendedPath]. */
    private fun shown(intendedPath: String?) = JournalDocView(intendedPath, deleted = false, trashed = false)

    private fun objectPath(bytes: ByteArray): String = JournalPaths.objectPath(sha256Bytes(bytes))

    /**
     * The index's [rows] and the fold's [folded] documents as a check reads them; [moved] is what a
     * fresh read of a document says once the walk is done, as a concurrent move or trash leaves it.
     */
    private class Source(
        var rows: List<DocumentEntity>,
        val sources: List<String>,
        var folded: Map<String, JournalDocView>,
        val held: Set<String> = emptySet(),
    ) : VerificationSource {
        var rereads = 0

        override suspend fun expectedDocuments(): List<ExpectedDocument> {
            val byId = rows.associateBy { it.docId }
            return (byId.keys + folded.keys).mapNotNull { ExpectedDocument.of(it, byId[it], folded[it]) }
        }

        override suspend fun expectedDocument(docId: String): ExpectedDocument? {
            rereads++
            return ExpectedDocument.of(docId, rows.firstOrNull { it.docId == docId }, folded[docId])
        }

        override suspend fun appliedSources(): List<String> = sources

        override suspend fun unheldSources(
            gone: List<String>,
            listed: Set<String>,
        ): Set<String> = gone.filterTo(HashSet()) { it !in held }
    }

    private suspend fun LibraryVerifier.run(
        rows: List<DocumentEntity>,
        sources: List<String> = emptyList(),
        folded: Map<String, JournalDocView> = emptyMap(),
        held: Set<String> = emptySet(),
        onProgress: (LibraryVerificationProgress) -> Unit = {},
    ): LibraryVerificationReport = verify(Source(rows, sources, folded, held), onProgress)

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
    fun knownMissingDocumentsAreTheirOwnResultNamedByTheirLibraryPath() =
        runTest {
            val f = fixture()
            val known = row("known missing".encodeToByteArray(), relPath = "Taxes/2024.pdf").copy(missing = true)
            // A row whose path another document took waits at its sentinel; the journal still knows
            // where it belongs, and the report names that, never the internal name.
            val parked =
                row("parked missing".encodeToByteArray()).let {
                    it.copy(relPath = missingSentinel(it.docId), missing = true)
                }
            val newlyMissing = row("newly missing".encodeToByteArray(), relPath = "New.pdf")

            val progress = mutableListOf<LibraryVerificationProgress>()
            val report =
                f.verifier.run(
                    listOf(known, parked, newlyMissing),
                    folded = mapOf(parked.docId to shown("Archive/Parked.pdf")),
                    onProgress = progress::add,
                )

            report.ok shouldBe false
            report.documentsChecked shouldBe 3
            report.documentProblems.map { it.docId } shouldContainExactly listOf(newlyMissing.docId)
            report.documentProblems.single().kind shouldBe VerificationProblemKind.DOCUMENT_MISSING
            report.knownMissing.map { it.relPath } shouldContainExactly listOf("Archive/Parked.pdf", "Taxes/2024.pdf")
            report.knownMissing.all { it.openable } shouldBe true
            report.problems.none { it.relPath.orEmpty().startsWith("${NpnPaths.NPN_DIR}/") } shouldBe true
            progress.first() shouldBe LibraryVerificationProgress(VerificationPhase.DOCUMENTS, 0, 3)
        }

    @Test
    fun aKnownMissingDocumentWhoseFileIsBackIsNoProblem() =
        runTest {
            val f = fixture()
            val bytes = "back again".encodeToByteArray()
            f.fs.writeBytesOnce("Back.pdf", bytes)

            val report = f.verifier.run(listOf(row(bytes, relPath = "Back.pdf").copy(missing = true)))

            report.ok shouldBe true
        }

    @Test
    fun aDocumentTheJournalShowsIsExpectedEvenWhenTheIndexLostItsRow() =
        runTest {
            val f = fixture()
            val lost = sha256Bytes("lost with its row".encodeToByteArray())
            val untitled = sha256Bytes("lost, no path".encodeToByteArray())
            val here = "still here".encodeToByteArray()
            f.fs.writeBytesOnce("Filed/Here.pdf", here)

            val report =
                f.verifier.run(
                    rows = emptyList(),
                    folded =
                        mapOf(
                            lost to shown("Filed/Lost.pdf"),
                            untitled to shown(null),
                            sha256Bytes(here) to shown("Filed/Here.pdf"),
                            "trashed" to JournalDocView(null, deleted = false, trashed = true),
                        ),
                )

            report.documentsChecked shouldBe 3
            report.documentProblems.map { it.kind }.toSet() shouldBe setOf(VerificationProblemKind.DOCUMENT_MISSING)
            report.documentProblems.associate { it.docId to (it.relPath to it.fileName) } shouldBe
                mapOf(lost to ("Filed/Lost.pdf" to "Lost.pdf"), untitled to (null to null))
            report.documentProblems.none { it.openable } shouldBe true
        }

    @Test
    fun aDocumentTheJournalHidesIsNotExpectedWhateverItsRowSays() =
        runTest {
            val f = fixture()
            val superseded = row("the old version".encodeToByteArray(), relPath = "Old.pdf")

            val report =
                f.verifier.run(
                    rows = listOf(superseded),
                    folded =
                        mapOf(
                            superseded.docId to
                                JournalDocView(
                                    null,
                                    deleted = false,
                                    trashed = false,
                                    supersededBy = "new",
                                ),
                        ),
                )

            report.ok shouldBe true
            report.documentsChecked shouldBe 0
        }

    @Test
    fun aDocumentMovedDuringTheWalkIsCheckedWhereItIsNow() =
        runTest {
            val f = fixture()
            val bytes = "moved while checked".encodeToByteArray()
            val before = row(bytes, relPath = "Inbox/Scan.pdf")
            // The walk lists the folder once the file has moved, and reads the row from before.
            f.fs.writeBytesOnce("Filed/Scan.pdf", bytes)
            val source = Source(listOf(before), emptyList(), emptyMap())
            val moved = before.copy(relPath = "Filed/Scan.pdf", fileName = "Scan.pdf")

            val report =
                f.verifier.verify(source) { progress ->
                    if (progress.phase == VerificationPhase.DOCUMENTS && progress.done == 1) source.rows = listOf(moved)
                }

            source.rereads shouldBe 1
            report.ok shouldBe true
        }

    @Test
    fun aDocumentTrashedDuringTheWalkIsNoProblem() =
        runTest {
            val f = fixture()
            val doc = row("trashed while checked".encodeToByteArray())
            val source = Source(listOf(doc), emptyList(), emptyMap())

            val report =
                f.verifier.verify(source) { progress ->
                    if (progress.phase == VerificationPhase.DOCUMENTS && progress.done == 1) {
                        source.folded = mapOf(doc.docId to JournalDocView(null, deleted = false, trashed = true))
                    }
                }

            report.ok shouldBe true
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
            // Once in the walk and once more in the re-check before the report.
            f.fs.faults.arm(SimFaults.Rule(FsMethod.SHA256, "Invoice.pdf", SimFaults.Point.BEFORE, 2))

            val report = f.verifier.run(listOf(row(bytes)))

            report.documentProblems.single().kind shouldBe VerificationProblemKind.DOCUMENT_UNREADABLE
        }

    @Test
    fun aReadThatFailsOnceIsNoProblemOnceTheRecheckReadsIt() =
        runTest {
            val f = fixture()
            val bytes = "read on the second try".encodeToByteArray()
            f.fs.writeBytesOnce("Invoice.pdf", bytes)
            f.fs.faults.arm(SimFaults.Rule(FsMethod.SHA256, "Invoice.pdf", SimFaults.Point.BEFORE, 1))

            f.verifier.run(listOf(row(bytes))).ok shouldBe true
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
    fun aGoneSourceWhoseEventsAnotherHoldsIsNotMissing() =
        runTest {
            val f = fixture()
            val copy =
                JournalPaths.objectPath("b".repeat(64)).removeSuffix(".jsonl") +
                    ".sync-conflict-20260101-000000-ABC.jsonl"
            val lost = JournalPaths.objectPath("c".repeat(64))

            val report = f.verifier.run(rows = emptyList(), sources = listOf(copy, lost), held = setOf(copy))

            report.objectProblems.map { it.relPath } shouldContainExactly listOf(lost)
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
            f.fs.writeBytesOnce("Lease.pdf/inside.pdf", bytes)
            val row = row(bytes, relPath = "Lease.pdf")

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
        }

    @Test
    fun anObjectStoreOfANewerBuildIsNoDamage() =
        runTest {
            val f = fixture()
            val hash = "e".repeat(64)
            f.fs.writeBytesOnce(".npn/journal/objects-v3-${hash.take(2)}/$hash.jsonl", "{}".encodeToByteArray())

            val report = f.verifier.run(rows = emptyList())

            report.problems.shouldBeEmpty()
            report.objectsChecked shouldBe 1
        }

    @Test
    fun aFileBesideTheBucketsIsNoObjectAndNoDamage() =
        runTest {
            val f = fixture()
            f.fs.writeBytesOnce("${NpnPaths.JOURNAL_DIR}/README.txt", "notes".encodeToByteArray())

            val report = f.verifier.run(rows = emptyList())

            report.problems.shouldBeEmpty()
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
