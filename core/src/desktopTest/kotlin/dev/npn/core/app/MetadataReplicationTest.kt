package dev.npn.core.app

import dev.npn.core.db.ProjectionStatus
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.requireByDocId
import dev.npn.core.journal.CompactionCandidate
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalEventVersions
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.journal.StorageV2Reader
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Metadata written on one device reaches another through the journal objects, snapshots and legacy
 * segments it reads, and a journal it cannot read blocks writes.
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
            session2.rescan() // folds device 1's segment
            session2.meta.addTag(listOf(id), "fromB")

            // Device 1 rescans, picking up device 2's segment -> both tags present.
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
                rig.fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
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
     * A snapshot produced by device 1 (superseding several of its objects, with those objects
     * deleted) is folded by device 2 via a plain rescan, and the superseded-object deletions do
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

            // Build a snapshot over device 1's objects, publish it, and delete the objects.
            val objects =
                rig.fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
            val candidates =
                objects.map { file ->
                    val bytes = rig.fs.readBytes(file.relPath)
                    CompactionCandidate(
                        file.relPath,
                        sha256Bytes(bytes),
                        StorageV2Reader
                            .read(file.relPath, bytes)
                            .let { it as JournalSourceRead.Accepted }
                            .events
                            .map { c -> c.event },
                    )
                }
            val compactor = SnapshotCompactor(rig.fs, "test-installation")
            compactor.publish(candidates).shouldNotBeNull()
            objects.forEach { rig.fs.delete(it.relPath) }

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

            assertFailsWith<MetadataWriteBlocked> { rig.session.rescan() }
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
            assertFailsWith<IllegalStateException> {
                rig.session.meta.addTag(listOf(id), "must-not-write")
            }
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
            val canonical =
                rig.fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .single {
                        it.relPath !in beforeTag &&
                            JournalPaths.classify(it.relPath) is JournalPath.ObjectV2
                    }
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

    /**
     * Legacy v1 segments remain readable: a REMOTE v1 device's segment is written directly (v1 is
     * no longer produced locally), applied via rescan, then rewritten with fewer events. The
     * tolerant v1 reader + shrink handling must drop events the rewritten source no longer contains.
     */
    @Test
    fun rewrittenLegacySourceDropsEventsItNoLongerContains() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            // A remote v1 device drops a legacy segment containing a tag we will later rewrite away.
            val remoteDevice = "remote-v1-device"
            val legacyPath = "${NpnPaths.JOURNAL_DIR}/$remoteDevice/0001.jsonl"
            val original =
                JournalEvent(
                    version = JournalEventVersions.V1,
                    seq = 1,
                    hlc = Hlc(90_000L, 0, remoteDevice),
                    dev = remoteDevice,
                    doc = id,
                    op = JournalOp.TagAdd("removed-by-rewrite"),
                )
            rig.fs.writeBytesOnce(legacyPath, JournalCodec.encodeLines(listOf(original)).encodeToByteArray())
            rig.session.rescan()
            var items =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(items, id) shouldContain "removed-by-rewrite"

            // The remote device rewrites the same segment file with a different (larger) content.
            val replacement =
                JournalEvent(
                    version = JournalEventVersions.V1,
                    seq = 1,
                    hlc = Hlc(99_000L, 0, "replacement-device"),
                    dev = "replacement-device",
                    doc = id,
                    op = JournalOp.TagAdd("replacement"),
                )
            val bytes = (JournalCodec.encodeLines(listOf(replacement)) + "\n".repeat(256)).encodeToByteArray()
            Files.write(rig.libRoot.resolve(legacyPath), bytes)

            rig.session.rescan()

            items =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(items, id) shouldNotContain "removed-by-rewrite"
            tagsOf(items, id) shouldContain "replacement"
        }
}
