package dev.npn.core.app

import dev.npn.core.db.ProjectionStatus
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
import dev.npn.core.index.requireByDocId
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.StorageV2Reader
import dev.npn.core.journal.compactAsAnEarlierBuild
import dev.npn.core.journal.journalObjects
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Metadata written on one device reaches another through the journal objects and snapshots it reads,
 * and a journal it cannot read blocks writes.
 */
class MetadataReplicationTest {
    @Test
    fun twoWritersConvergeAfterRescan() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            // Device 1 tags it.
            rig.session.meta.addTag(listOf(id), "fromA")

            // Device 2 opens the SAME library (separate cache, distinct deviceId).
            val (session2, _) = rig.secondSession(backgroundScope)
            session2.rescan() // folds device 1's object
            session2.meta.addTag(listOf(id), "fromB")

            // Device 1 rescans, picking up device 2's object -> both tags present.
            rig.session.rescan()
            val docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(docs, id) shouldContainExactlyInAnyOrder listOf("fromA", "fromB")

            // Device 2 rescans -> also has both.
            session2.rescan()
            val docs2 = session2.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            docs2.first { it.doc.docId == id }.tags shouldContainExactlyInAnyOrder listOf("fromA", "fromB")

            session2.close()
        }

    @Test
    fun confirmedDesktopWriterPublishesV2AndBridgeReaderRebuildsIt() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.meta.addTag(listOf(id), "v2")
            val firstAllocator =
                rig.db
                    .metadataProjectionDao()
                    .state()
                    .shouldNotBeNull()
                    .allocatorPhysical
            rig.session.meta.setField(id, "title", "second transaction")
            (
                rig.db
                    .metadataProjectionDao()
                    .state()
                    .shouldNotBeNull()
                    .allocatorPhysical > firstAllocator
            ) shouldBe true

            val objects =
                rig.fs.journalObjects()
            // Two user transactions plus the one-time field-registry seed written on open.
            objects.size shouldBe 3
            objects.forEach { objectFile ->
                assertIs<JournalSourceRead.Accepted>(
                    StorageV2Reader.read(objectFile.relPath, rig.fs.readBytes(objectFile.relPath)),
                )
            }

            val (bridgeSession, _) = rig.secondSession(backgroundScope)
            bridgeSession.rescan()
            val items = bridgeSession.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            tagsOf(items, id) shouldContain "v2"
            bridgeSession.close()
        }

    @Test
    fun bridgeReaderAppliesRemoteV2LifecycleEvents() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val (bridgeSession, db2) = rig.secondSession(backgroundScope)
            bridgeSession.rescan()

            rig.session.docLifecycle.trashDoc(id)
            bridgeSession.rescan()
            db2.metadataProjectionDao().allEvents().map { it.operation } shouldContain "doc-trash"
            db2.documentDao().requireByDocId(id).trashed shouldBe true

            bridgeSession.close()
        }

    /**
     * A snapshot an earlier build produced on device 1 (superseding several of its objects, with
     * those objects deleted) is folded by device 2 via a plain rescan, and the superseded-object deletions do
     * NOT block device 2 because an applied snapshot lists their hashes.
     */
    @Test
    fun remoteSnapshotSupersedesDeletedObjectsWithoutBlockingSecondDevice() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            repeat(5) { i -> rig.session.meta.addTag(listOf(id), "tag$i") }

            // Device 2 folds all five objects first.
            val (session2, db2) = rig.secondSession(backgroundScope)
            session2.rescan()
            db2.tagDao().tagsForDocument(id).size shouldBe 5

            // An earlier build on device 1 compacts its objects into a snapshot and deletes them.
            rig.fs.compactAsAnEarlierBuild(rig.fs.journalObjects().map { it.relPath })

            // Device 2 rescans: applies the snapshot, tolerates the now-missing objects.
            session2.rescan()
            db2.tagDao().tagsForDocument(id).size shouldBe 5
            db2
                .metadataProjectionDao()
                .state()
                .shouldNotBeNull()
                .migrationState shouldBe ProjectionStatus.READY
            session2.close()
        }

    @Test
    fun invalidV2ObjectLatchesWriteBlock() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.meta.addTag(listOf(id), "preserved")
            val partial = "{".encodeToByteArray()
            val path = JournalPaths.objectPath(sha256Bytes(partial))
            rig.fs.writeBytesOnce(path, partial)

            assertFailsWith<MetadataWriteBlocked> { rig.session.rescan() }.block shouldBe WriteBlock.JOURNAL_DAMAGED
            rig.db
                .metadataProjectionDao()
                .state()
                .shouldNotBeNull()
                .migrationState shouldBe ProjectionStatus.FAILED
            val items =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(items, id) shouldContain "preserved"
            assertFailsWith<MetadataWriteBlocked> {
                rig.session.meta.addTag(listOf(id), "must-not-write")
            }.block shouldBe WriteBlock.JOURNAL_DAMAGED
        }

    /**
     * An object holding an op only a newer build knows blocks writes, and every refused write says
     * so, as the reason the user is told: the app needs updating, the journal is not damaged.
     */
    @Test
    fun anOpFromANewerBuildBlocksWritesAsANewerFormat() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.fs.writeNewerBuildsObject()

            assertFailsWith<MetadataWriteBlocked> { rig.session.rescan() }.block shouldBe WriteBlock.NEWER_FORMAT
            assertFailsWith<MetadataWriteBlocked> {
                rig.session.meta.addTag(listOf(id), "refused")
            }.block shouldBe WriteBlock.NEWER_FORMAT
        }

    @Test
    fun validConflictCopyPathReplacesCanonicalObjectPath() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val beforeTag =
                rig.fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .map { it.relPath }
                    .toSet()
            rig.session.meta.addTag(listOf(id), "kept")
            val canonical = rig.fs.journalObjects().single { it.relPath !in beforeTag }
            val conflictName = "${canonical.name.removeSuffix(".jsonl")} (conflicted copy laptop).jsonl"
            val conflictPath = canonical.relPath.substringBeforeLast('/') + "/" + conflictName
            rig.fs.move(canonical.relPath, conflictPath)

            rig.session.rescan()
            val items =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(items, id) shouldContain "kept"
        }
}
