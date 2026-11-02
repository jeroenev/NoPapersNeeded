package dev.npn.core.sync

import dev.npn.core.fs.NpnPaths
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlin.test.Test

/**
 * Two libraries converging through a directory-backed remote: the happy paths — install, rename,
 * trash, restore, delete and metadata — plus the manifest and service summary. Real sessions, real
 * journal, real index; only the transport is a directory, so these cover everything but rclone
 * itself. The guard and corruption cases live in [SyncEngineGuardTest].
 */
internal class SyncEngineTest : SyncEngineTestBase() {
    @Test
    fun documentsAndMetadataConvergeAcrossReplicas() =
        withPair { pair ->
            val docId = pair.importOnA("invoice.pdf", "one")
            pair.a.session.meta
                .addTag(listOf(docId), "bills")

            val first = pair.a.session.syncCompleted()
            first.uploadedDocuments shouldBe 1
            // The tag transaction plus whatever the first open seeded (field definitions).
            first.uploadedObjects shouldBeGreaterThanOrEqual 1
            pair.remote.exists("inbox/invoice.pdf").shouldBeTrue()
            pair.remote
                .paths()
                .any { it.startsWith("${NpnPaths.JOURNAL_DIR}/objects-v2-") }
                .shouldBeTrue()

            val pulled = pair.b.session.syncCompleted()
            pulled.downloadedDocuments shouldBe 1
            pulled.errors.shouldBeEmpty()
            pair.b.exists("inbox/invoice.pdf").shouldBeTrue()
            pair.b.fs.sha256("inbox/invoice.pdf") shouldBe docId
            val row =
                pair.b.db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.relPath shouldBe "inbox/invoice.pdf"
            pair.b.db
                .tagDao()
                .tagsForDocument(docId) shouldContainExactly listOf("bills")

            // A's next pass only collects what B's first open seeded (its own settings transaction);
            // no document moves, and after that both sides are quiescent.
            val secondA = pair.a.session.syncCompleted()
            secondA.downloadedDocuments shouldBe 0
            secondA.uploadedDocuments shouldBe 0
            secondA.uploadedObjects shouldBe 0
            pair.a.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
            pair.b.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
        }

    @Test
    fun aRenameFollowsTheJournaledIntentOnBothSidesOfTheRemote() =
        withPair { pair ->
            val docId = pair.importOnA("draft.pdf", "two")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()

            pair.b.session.docLifecycle
                .renameFile(docId, "final.pdf")
            pair.b.exists("inbox/final.pdf").shouldBeTrue()
            val pushed = pair.b.session.syncCompleted()
            pushed.movedRemote shouldBe 1
            pair.remote.exists("inbox/final.pdf").shouldBeTrue()
            pair.remote.exists("inbox/draft.pdf").shouldBeFalse()

            val pulled = pair.a.session.syncCompleted()
            pulled.downloadedDocuments shouldBe 0
            pair.a.exists("inbox/final.pdf").shouldBeTrue()
            pair.a.exists("inbox/draft.pdf").shouldBeFalse()
            pair.a.db
                .documentDao()
                .getByDocId(docId)!!
                .relPath shouldBe "inbox/final.pdf"
            pair.a.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
        }

    @Test
    fun trashAndRestoreAreMirroredWithoutResurrection() =
        withPair { pair ->
            val docId = pair.importOnA("old.pdf", "three")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()

            pair.a.session.docLifecycle
                .trashDoc(docId)
            val trashed = pair.a.session.syncCompleted()
            trashed.movedRemote shouldBe 1
            pair.remote.exists("inbox/old.pdf").shouldBeFalse()
            pair.remote.paths().filter { it.startsWith("${NpnPaths.TRASH_DIR}/") } shouldHaveSize 1

            val mirrored = pair.b.session.syncCompleted()
            mirrored.appliedLocally shouldBe 1
            pair.b.exists("inbox/old.pdf").shouldBeFalse()
            soleFileUnder(pair.b.libRoot.resolve(NpnPaths.TRASH_DIR)).shouldNotBeNull()
            pair.b.db
                .documentDao()
                .getByDocId(docId)!!
                .trashed
                .shouldBeTrue()
            // A rescan after the pass does not bring the document back.
            pair.b.session.ingest
                .rescan()
            pair.b.db
                .documentDao()
                .getByDocId(docId)!!
                .trashed
                .shouldBeTrue()

            pair.a.session.docLifecycle
                .restoreDoc(docId)
            pair.a.exists("inbox/old.pdf").shouldBeTrue()
            pair.a.session
                .syncCompleted()
                .movedRemote shouldBe 1
            pair.remote.exists("inbox/old.pdf").shouldBeTrue()

            val restored = pair.b.session.syncCompleted()
            restored.downloadedDocuments shouldBe 0 // bytes came back from B's own trash
            pair.b.exists("inbox/old.pdf").shouldBeTrue()
            pair.b.fs.sha256("inbox/old.pdf") shouldBe docId
            val row =
                pair.b.db
                    .documentDao()
                    .getByDocId(docId)!!
            row.trashed.shouldBeFalse()
            row.relPath shouldBe "inbox/old.pdf"
        }

    @Test
    fun aPermanentDeleteRemovesEveryCopy() =
        withPair { pair ->
            val docId = pair.importOnA("gone.pdf", "four")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()

            // Only a trashed document is deleted for good.
            pair.a.session.docLifecycle
                .trashDoc(docId)
            pair.a.session.docLifecycle
                .deleteDocPermanently(docId)
            val deleted = pair.a.session.syncCompleted()
            deleted.deletedRemote shouldBe 1
            pair.remote.exists("inbox/gone.pdf").shouldBeFalse()

            val mirrored = pair.b.session.syncCompleted()
            mirrored.appliedLocally shouldBe 1
            pair.b.exists("inbox/gone.pdf").shouldBeFalse()
            pair.b.db
                .documentDao()
                .getByDocId(docId)!!
                .deleted
                .shouldBeTrue()
            pair.b.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
            pair.remote.exists("inbox/gone.pdf").shouldBeFalse()
        }

    @Test
    fun anInterruptedDownloadLeavesNothingInTheLibraryAndIsRetried() =
        withPair { pair ->
            pair.importOnA("big.pdf", "five")
            pair.a.session.syncCompleted()

            pair.remote.failDownloads += "inbox/big.pdf"
            val failed = pair.b.session.syncCompleted()
            failed.errors.map { it.path } shouldContain "inbox/big.pdf"
            failed.downloadedDocuments shouldBe 0
            pair.b.exists("inbox/big.pdf").shouldBeFalse()
            pair.b.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .shouldBeEmpty()

            pair.remote.failDownloads.clear()
            val retried = pair.b.session.syncCompleted()
            retried.downloadedDocuments shouldBe 1
            retried.errors.shouldBeEmpty()
            pair.b.exists("inbox/big.pdf").shouldBeTrue()
        }

    @Test
    fun aDocumentDroppedOnTheRemoteByAnotherToolIsInstalledAndIndexed() =
        withPair(reportsHashes = true) { pair ->
            pair.remote.put("scans/from-phone.pdf", pdf("six"))
            val report = pair.b.session.syncCompleted()
            report.downloadedDocuments shouldBe 1
            // With native hashes the remote copy is identified without a download and then
            // downloaded exactly once for installation.
            pair.remote.downloads shouldBe 1
            pair.b.exists("scans/from-phone.pdf").shouldBeTrue()
            val row =
                pair.b.db
                    .documentDao()
                    .getByRelPath("scans/from-phone.pdf")
                    .shouldNotBeNull()
            row.docId shouldBe pair.b.fs.sha256("scans/from-phone.pdf")
        }

    @Test
    fun anUnknownRemoteIsIdentifiedByDownloadOnceAndRememberedInTheManifest() =
        withPair { pair ->
            pair.remote.put("scans/a.pdf", pdf("seven"))
            pair.b.session
                .syncCompleted()
                .downloadedDocuments shouldBe 1
            pair.remote.downloads shouldBe 1
            val manifest =
                pair.b.deviceDb
                    .syncRemoteDao()
                    .all()
            manifest.map { it.remotePath } shouldContain "scans/a.pdf"
            manifest.first { it.remotePath == "scans/a.pdf" }.docId shouldBe pair.b.fs.sha256("scans/a.pdf")

            // The manifest answers for the unchanged entry: no further download on the next pass.
            pair.b.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
            pair.remote.downloads shouldBe 1
        }

    @Test
    fun aDocumentAlreadyHeldLocallyIsNotDuplicatedWhenTheRemoteHasItElsewhere() =
        withPair { pair ->
            pair.a.session.syncCompleted()
            val docId = pair.importOnA("same.pdf", "eight")
            pair.remote.put("elsewhere/copy.pdf", pdf("eight"))
            val report = pair.a.session.syncCompleted()
            report.downloadedDocuments shouldBe 0
            // Nothing goes up twice: the two were never reconciled, so the library file takes the
            // name the remote already has for the document.
            report.uploadedDocuments shouldBe 0
            report.movedRemote shouldBe 0
            pair.remote.paths().filter { it.endsWith(".pdf") && !it.startsWith(".npn/") } shouldContainExactly
                listOf("elsewhere/copy.pdf")
            pair.a.session.ingest
                .rescan()
            pair.a.db
                .documentDao()
                .getByDocId(docId)!!
                .relPath shouldBe "elsewhere/copy.pdf"
            pair.a.fs
                .listAllIndexable()
                .count { !it.isDirectory } shouldBe 1
        }

    @Test
    fun switchingRemotesForgetsTheManifest() =
        withPair { pair ->
            pair.importOnA("x.pdf", "nine")
            pair.a.session.syncCompleted()
            pair.a.deviceDb
                .syncRemoteDao()
                .all()
                .shouldNotBeEmpty()

            val manifest = SyncManifest(pair.a.deviceDb.syncRemoteDao(), pair.a.deviceDb.keyValueDao())
            manifest.load("other:remote")
            manifest.entries.shouldBeEmpty()
            pair.a.deviceDb
                .syncRemoteDao()
                .all()
                .shouldBeEmpty()
        }

    @Test
    fun theServiceReportsStatusAndPersistsTheLastRun() =
        withPair { pair ->
            pair.importOnA("s.pdf", "ten")
            pair.a.session.sync.status
                .first()
                .lastRun
                .shouldBeNull()
            pair.a.session.syncCompleted()
            val status =
                pair.a.session.sync.status
                    .first()
            status.running.shouldBeFalse()
            status.lastRun
                .shouldNotBeNull()
                .ok
                .shouldBeTrue()
            pair.a.deviceDb
                .keyValueDao()
                .get(SyncManifest.KEY_LAST_RUN)
                .shouldNotBeNull()

            // Automatic passes stay off until the user turns them on.
            pair.a.session.sync
                .syncScheduled()
                .shouldBeInstanceOf<SyncOutcome.NotConfigured>()
        }
}
