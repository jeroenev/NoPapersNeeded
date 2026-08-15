package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalEventVersions
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.StorageV2Reader
import dev.npn.core.model.DocProperty
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MetadataWriterTest {
    private fun tagsOf(
        items: List<DocListItem>,
        docId: String,
    ): List<String> = items.first { it.doc.docId == docId }.tags

    @Test
    fun addTagThenRemoveTag() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                rig.session.meta.addTag(listOf(id), "invoice")
                var docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(docs, id) shouldContain "invoice"

                rig.session.meta.removeTag(listOf(id), "invoice")
                docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(docs, id) shouldNotContain "invoice"
            } finally {
                rig.close()
            }
        }

    @Test
    fun setReviewedReflected() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                rig.session.meta.setReviewed(listOf(id), true)
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .reviewed shouldBe true
            } finally {
                rig.close()
            }
        }

    @Test
    fun deleteTagRemovesFromAllDocs() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.docId("a.pdf")
                val b = rig.docId("b.pdf")

                rig.session.meta.addTag(listOf(a, b), "shared")
                var docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(docs, a) shouldContain "shared"
                tagsOf(docs, b) shouldContain "shared"

                rig.session.tagAdmin.deleteTag("shared")
                docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(docs, a) shouldNotContain "shared"
                tagsOf(docs, b) shouldNotContain "shared"
            } finally {
                rig.close()
            }
        }

    @Test
    fun unusedTagsArePrunedFromTagList() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                rig.session.meta.addTag(listOf(id), "fleeting")
                rig.session.tags
                    .observeTags()
                    .first()
                    .map { it.name } shouldContain "fleeting"

                // Untagging the last document drops the tag from the list entirely (no count-0 rows).
                rig.session.meta.removeTag(listOf(id), "fleeting")
                rig.session.tags
                    .observeTags()
                    .first()
                    .map { it.name } shouldNotContain "fleeting"

                // A style alone does not keep an unused tag alive, but it survives in the journal and
                // comes back when the tag is used again.
                rig.session.tagAdmin.setTagStyle("styled", "#ff0000", null)
                rig.session.tags
                    .observeTags()
                    .first()
                    .map { it.name } shouldNotContain "styled"
                rig.session.meta.addTag(listOf(id), "styled")
                val styled =
                    rig.session.tags
                        .observeTags()
                        .first()
                        .first { it.name == "styled" }
                styled.color shouldBe "#ff0000"

                // deleteTag prunes as well.
                rig.session.tagAdmin.deleteTag("styled")
                rig.session.tags
                    .observeTags()
                    .first()
                    .map { it.name } shouldNotContain "styled"
            } finally {
                rig.close()
            }
        }

    @Test
    fun setCorrespondentAndDocType() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                rig.session.meta.setCorrespondent(listOf(id), "ACME")
                rig.session.meta.setDocType(listOf(id), "Invoice")
                val doc = rig.db.documentDao().getByDocId(id)!!
                doc.correspondent shouldBe "ACME"
                doc.docType shouldBe "Invoice"

                rig.session.documents
                    .observeCorrespondents()
                    .first() shouldContain "ACME"
                rig.session.documents
                    .observeDocTypes()
                    .first() shouldContain "Invoice"
            } finally {
                rig.close()
            }
        }

    @Test
    fun setFieldProjectsToDbAndObserveDocAndKeys() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                rig.session.meta.setField(id, "amount", "42.50")
                rig.session.meta.setField(id, "vendor", "ACME")

                // Projected into document_fields and surfaced via observeDoc.
                val item =
                    rig.session.documents
                        .observeDoc(id)
                        .first()!!
                item.fields shouldBe mapOf("amount" to "42.50", "vendor" to "ACME")

                // Distinct keys available for autocomplete.
                rig.session.documents
                    .observeFieldKeys()
                    .first() shouldContainExactlyInAnyOrder
                    listOf("amount", "vendor")

                // Clearing a field removes it.
                rig.session.meta.setField(id, "amount", null)
                rig.session.documents
                    .observeDoc(id)
                    .first()!!
                    .fields shouldBe mapOf("vendor" to "ACME")
            } finally {
                rig.close()
            }
        }

    /**
     * [MetadataWriter.setOcrEngine] pins the per-document OCR engine as a `prop-set` journal
     * event for key `ocrEngine` (the [DocProperty.OCR_ENGINE] wire key), which the Indexer
     * projects onto [DocumentEntity.ocrEngineOverride]. Clearing (null) removes the override.
     */
    @Test
    fun setOcrEngineCommitsPropSetAndProjectsOverride() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                rig.session.ocr.setOcrEngine(id, "tesseract")

                // Committed as a prop-set event carrying the "ocrEngine" property key.
                val events = rig.db.metadataProjectionDao().allEvents()
                val propSet = events.filter { it.operation == "prop-set" }
                propSet.map { it.documentId } shouldContain id
                propSet.any { it.canonicalJson.contains("\"ocrEngine\"") } shouldBe true

                // Projected onto the override column (not the indexed-version column).
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .ocrEngineOverride shouldBe "tesseract"

                // Clearing removes the override.
                rig.session.ocr.setOcrEngine(id, null)
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .ocrEngineOverride shouldBe null
            } finally {
                rig.close()
            }
        }

    /**
     * Regression: [DocumentRepository.observeDoc] must re-emit on tag changes without a
     * re-subscribe (the detail view used to read tags one-shot and only re-emitted on
     * `documents`/`document_fields` invalidations, leaving the screen stale until reopened).
     * Each collector below subscribes BEFORE the write, so its predicate can only be satisfied
     * by a re-emission of the live flow.
     */
    @Test
    fun observeDocReEmitsTagEditsOnLiveSubscription() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val flow = rig.session.documents.observeDoc(id)

                coroutineScope {
                    val added =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            flow.first { it != null && "invoice" in it.tags }
                        }
                    rig.session.meta.addTag(listOf(id), "invoice")
                    added.await()

                    val removed =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            flow.first { it != null && "invoice" !in it.tags }
                        }
                    rig.session.meta.removeTag(listOf(id), "invoice")
                    removed.await()

                    val readded =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            flow.first { it != null && "invoice" in it.tags }
                        }
                    rig.session.meta.addTag(listOf(id), "invoice")
                    readded.await()
                }
            } finally {
                rig.close()
            }
        }

    @Test
    fun twoWritersConvergeAfterRescan() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            var db2 = rig.db
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                // Device 1 tags it.
                rig.session.meta.addTag(listOf(id), "fromA")

                // Device 2 opens the SAME library (separate cache, distinct deviceId).
                val (session2, secondDb) = rig.secondSession(backgroundScope)
                db2 = secondDb
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
            } finally {
                if (db2 !== rig.db) db2.close()
                rig.close()
            }
        }

    @Test
    fun confirmedDesktopWriterPublishesV2AndBridgeReaderRebuildsIt() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            var secondDb = rig.db
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.addTag(listOf(id), "v2")
                val firstAllocator =
                    rig.db
                        .metadataProjectionDao()
                        .state()!!
                        .allocatorPhysical
                rig.session.meta.setTitle(id, "second transaction")
                (
                    rig.db
                        .metadataProjectionDao()
                        .state()!!
                        .allocatorPhysical > firstAllocator
                ) shouldBe true

                val objects =
                    rig.fs
                        .listAllUnder(NpnPaths.JOURNAL_DIR)
                        .filter { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.ObjectV2 }
                objects.size shouldBe 2
                objects.forEach { objectFile ->
                    assertIs<JournalSourceRead.Accepted>(
                        StorageV2Reader.read(objectFile.relPath, rig.fs.readBytes(objectFile.relPath)),
                    )
                }

                val (bridgeSession, db2) = rig.secondSession(backgroundScope)
                secondDb = db2
                bridgeSession.rescan()
                val items = bridgeSession.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
                tagsOf(items, id) shouldContain "v2"
                bridgeSession.close()
            } finally {
                if (secondDb !== rig.db) secondDb.close()
                rig.close()
            }
        }

    @Test
    fun bridgeReaderAppliesRemoteV2LifecycleEvents() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            var secondDb = rig.db
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val (bridgeSession, db2) = rig.secondSession(backgroundScope)
                secondDb = db2
                bridgeSession.rescan()

                rig.session.docLifecycle.trashDoc(id)
                bridgeSession.rescan()
                db2.metadataProjectionDao().allEvents().map { it.operation } shouldContain "doc-trash"
                db2.documentDao().getByDocId(id)!!.trashed shouldBe true

                bridgeSession.close()
            } finally {
                if (secondDb !== rig.db) secondDb.close()
                rig.close()
            }
        }

    /**
     * A snapshot produced by device 1 (superseding several of its objects, with those objects
     * deleted) is folded by device 2 via a plain rescan, and the superseded-object deletions do
     * NOT block device 2 because an applied snapshot lists their hashes.
     */
    @Test
    fun remoteSnapshotSupersedesDeletedObjectsWithoutBlockingSecondDevice() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            var secondDb = rig.db
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                repeat(5) { i -> rig.session.meta.addTag(listOf(id), "tag$i") }

                // Device 2 folds all five objects first.
                val (session2, db2) = rig.secondSession(backgroundScope)
                secondDb = db2
                session2.rescan()
                db2.tagDao().tagsForDocument(id).size shouldBe 5

                // Build a snapshot over device 1's objects, publish it, and delete the objects.
                val objects =
                    rig.fs
                        .listAllUnder(NpnPaths.JOURNAL_DIR)
                        .filter { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.ObjectV2 }
                val candidates =
                    objects.map { file ->
                        val bytes = rig.fs.readBytes(file.relPath)
                        dev.npn.core.journal.CompactionCandidate(
                            file.relPath,
                            sha256Bytes(bytes),
                            dev.npn.core.journal.StorageV2Reader
                                .read(file.relPath, bytes)
                                .let { it as JournalSourceRead.Accepted }
                                .events
                                .map { c -> c.event },
                            file.mtimeMillis,
                        )
                    }
                val compactor =
                    dev.npn.core.journal
                        .SnapshotCompactor(rig.fs, "test-installation")
                val published = compactor.publish(candidates)!!
                objects.forEach { rig.fs.delete(it.relPath) }

                // Device 2 rescans: applies the snapshot, tolerates the now-missing objects.
                session2.rescan()
                db2.tagDao().tagsForDocument(id).size shouldBe 5
                db2.metadataProjectionDao().state()!!.migrationState shouldBe MetadataAccumulator.MIGRATION_READY
                session2.close()
            } finally {
                if (secondDb !== rig.db) secondDb.close()
                rig.close()
            }
        }

    @Test
    fun invalidV2ObjectLatchesWriteBlock() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.addTag(listOf(id), "preserved")
                val partial = "{".encodeToByteArray()
                val path = NpnPaths.journalObjectPath(sha256Bytes(partial))
                rig.fs.writeBytesOnce(path, partial)

                assertFailsWith<MetadataWriteBlocked> { rig.session.rescan() }
                rig.db
                    .metadataProjectionDao()
                    .state()!!
                    .migrationState shouldBe MetadataAccumulator.MIGRATION_FAILED
                val items =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(items, id) shouldContain "preserved"
                assertFailsWith<IllegalStateException> {
                    rig.session.meta.addTag(listOf(id), "must-not-write")
                }
            } finally {
                rig.close()
            }
        }

    @Test
    fun validConflictCopyPathReplacesCanonicalObjectPath() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.addTag(listOf(id), "kept")
                val canonical =
                    rig.fs
                        .listAllUnder(NpnPaths.JOURNAL_DIR)
                        .single { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.ObjectV2 }
                val conflictName = "${canonical.name.removeSuffix(".jsonl")} (conflicted copy laptop).jsonl"
                val conflictPath = canonical.relPath.substringBeforeLast('/') + "/" + conflictName
                rig.fs.move(canonical.relPath, conflictPath)

                rig.session.rescan()
                val items =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(items, id) shouldContain "kept"
            } finally {
                rig.close()
            }
        }

    /**
     * Legacy v1 segments remain readable: a REMOTE v1 device's segment is written directly (v1 is
     * no longer produced locally), applied via rescan, then rewritten with fewer events. The
     * tolerant v1 reader + shrink handling must drop events the rewritten source no longer contains.
     */
    @Test
    fun rewrittenLegacySourceDropsEventsItNoLongerContains() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                // A remote v1 device drops a legacy segment containing a tag we will later rewrite away.
                val remoteDevice = "remote-v1-device"
                val legacyPath = NpnPaths.journalSegmentPath(remoteDevice, "0001.jsonl")
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
            } finally {
                rig.close()
            }
        }
}
