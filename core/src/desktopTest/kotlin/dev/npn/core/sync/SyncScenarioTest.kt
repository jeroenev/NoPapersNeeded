package dev.npn.core.sync

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * The qualification scenarios of `docs/SYNC_QUALIFICATION.md`, runnable against the fake remote in
 * [SyncTestSupport]: both sides editing while apart, simultaneous metadata edits, rename, trash and
 * restore, delete for good, a pass lost between an upload and its manifest write, a large file, a
 * third replica joining, a restore onto an empty device, and the documented rename-swap limit.
 *
 * Real sessions, real journal and index; only the remote is a directory. What this suite cannot
 * prove — rclone's own behaviour, a provider's API, OAuth expiry, rate limits, case folding, a
 * provider-side recycle bin, and a real process kill — is a manual cell of that document.
 */
internal class SyncScenarioTest : SyncEngineTestBase() {
    @Test
    fun offlineEditsOnBothSidesConverge() =
        withCluster { cluster ->
            val shared = cluster.a.importPdf("shared.pdf", "offline-shared")
            cluster.converge()

            // Both devices go offline and edit the same document and their own new documents.
            cluster.a.session.meta
                .addTag(listOf(shared), "from-a")
            val onA = cluster.a.importPdf("only-on-a.pdf", "offline-a")
            cluster.b.session.meta
                .addTag(listOf(shared), "from-b")
            val onB = cluster.b.importPdf("only-on-b.pdf", "offline-b")

            cluster.converge()

            for (rig in listOf(cluster.a, cluster.b)) {
                rig.exists("inbox/shared.pdf").shouldBeTrue()
                rig.exists("inbox/only-on-a.pdf").shouldBeTrue()
                rig.exists("inbox/only-on-b.pdf").shouldBeTrue()
                rig.db
                    .tagDao()
                    .tagsForDocument(shared)
                    .toSet() shouldBe setOf("from-a", "from-b")
                rig.db
                    .documentDao()
                    .getByDocId(onA)
                    .shouldNotBeNull()
                rig.db
                    .documentDao()
                    .getByDocId(onB)
                    .shouldNotBeNull()
            }
            // Nothing is left for another pass: no replica keeps re-uploading its own copy.
            cluster.converge() shouldBe 1
        }

    @Test
    fun simultaneousMetadataEditsKeepTheLaterWriteAndBothTags() =
        withCluster { cluster ->
            val doc = cluster.a.importPdf("edited.pdf", "simultaneous")
            cluster.converge()

            // Neither device has seen the other's edit. A's clock is later, so its value wins under
            // HLC last-writer-wins; the OR-set keeps both tags whatever the order.
            cluster.b.clock.now = 1_000_000
            cluster.b.session.meta
                .setField(doc, FieldKeys.TITLE, "B's title")
            cluster.b.session.meta
                .addTag(listOf(doc), "from-b")
            cluster.a.clock.now = 2_000_000
            cluster.a.session.meta
                .setField(doc, FieldKeys.TITLE, "A's title")
            cluster.a.session.meta
                .addTag(listOf(doc), "from-a")

            cluster.converge()

            for (rig in listOf(cluster.a, cluster.b)) {
                rig.db
                    .documentFieldDao()
                    .valuesOf(doc)[FieldKeys.TITLE] shouldBe "A's title"
                rig.db
                    .tagDao()
                    .tagsForDocument(doc)
                    .toSet() shouldBe setOf("from-a", "from-b")
                rig.db
                    .documentDao()
                    .getByDocId(doc)!!
                    .title shouldBe "A's title"
            }
            cluster.converge() shouldBe 1
        }

    @Test
    fun aRenameTravelsWhileTheOtherDeviceEditsMetadata() =
        withCluster { cluster ->
            val doc = cluster.a.importPdf("draft.pdf", "rename-travels")
            cluster.converge()

            cluster.a.session.docLifecycle
                .renameFile(doc, "final.pdf")
            cluster.b.session.meta
                .setField(doc, FieldKeys.CORRESPONDENT, "ACME")
            cluster.converge()

            for (rig in listOf(cluster.a, cluster.b)) {
                rig.exists("inbox/final.pdf").shouldBeTrue()
                rig.exists("inbox/draft.pdf").shouldBeFalse()
                rig.fs.sha256("inbox/final.pdf") shouldBe doc
                rig.db
                    .documentDao()
                    .getByDocId(doc)!!
                    .relPath shouldBe "inbox/final.pdf"
                rig.db
                    .documentFieldDao()
                    .valuesOf(doc)[FieldKeys.CORRESPONDENT] shouldBe "ACME"
            }
            cluster.remote.exists("inbox/final.pdf").shouldBeTrue()
            cluster.remote.exists("inbox/draft.pdf").shouldBeFalse()
            cluster.converge() shouldBe 1
        }

    @Test
    fun aTrashOnOneDeviceWinsOverAMetadataEditOnTheOtherAndRestoreBringsItBack() =
        withCluster { cluster ->
            val doc = cluster.a.importPdf("keep.pdf", "trash-restore")
            cluster.converge()

            cluster.a.session.docLifecycle
                .trashDoc(doc)
            cluster.a.session
                .syncCompleted()
                .movedRemote shouldBe 1
            // B was offline and edited the document while A trashed it.
            cluster.b.session.meta
                .addTag(listOf(doc), "seen-offline")

            cluster.b.session
                .syncCompleted()
                .appliedLocally shouldBe 1
            cluster.b.exists("inbox/keep.pdf").shouldBeFalse()
            cluster.b.db
                .documentDao()
                .getByDocId(doc)!!
                .trashed
                .shouldBeTrue()
            cluster.remote
                .paths()
                .filter { it.startsWith("${NpnPaths.TRASH_DIR}/") } shouldHaveSize 1

            cluster.b.session.docLifecycle
                .restoreDoc(doc)
            cluster.converge()

            for (rig in listOf(cluster.a, cluster.b)) {
                rig.exists("inbox/keep.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(doc)!!
                    .trashed
                    .shouldBeFalse()
                rig.db
                    .tagDao()
                    .tagsForDocument(doc) shouldContain "seen-offline"
            }
            cluster.remote.exists("inbox/keep.pdf").shouldBeTrue()
            cluster.remote
                .paths()
                .filter { it.startsWith("${NpnPaths.TRASH_DIR}/") }
                .shouldBeEmpty()
            cluster.converge() shouldBe 1
        }

    @Test
    fun aDeleteForGoodRemovesEveryCopyAndSurvivesAReplicaThatWasOffline() =
        withCluster { cluster ->
            val doc = cluster.a.importPdf("gone.pdf", "delete-everywhere")
            cluster.converge()

            cluster.a.session.docLifecycle
                .trashDoc(doc)
            cluster.a.session.docLifecycle
                .deleteDocPermanently(doc)
            // B was offline and still shows the document.
            cluster.b.exists("inbox/gone.pdf").shouldBeTrue()
            cluster.b.session.meta
                .addTag(listOf(doc), "offline-edit")

            cluster.converge()

            for (rig in listOf(cluster.a, cluster.b)) {
                rig.exists("inbox/gone.pdf").shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(doc)!!
                    .deleted
                    .shouldBeTrue()
            }
            cluster.remote
                .paths()
                .filter { it.endsWith(".pdf") }
                .shouldBeEmpty()
            cluster.remote
                .paths()
                .filter { it.startsWith("${NpnPaths.TRASH_DIR}/") }
                .shouldBeEmpty()
            cluster.converge() shouldBe 1
        }

    @Test
    fun aPassKilledAfterAnUploadLandsAdoptsTheRemoteCopyWithoutADuplicate() =
        withCluster { cluster ->
            cluster.a.importPdf("killed.pdf", "process-death")
            // The upload lands, then the pass dies before recording its identity in the manifest.
            cluster.remote.landThenFailUploads += "inbox/killed.pdf"

            val killed = cluster.a.session.syncCompleted()
            killed.errors.map { it.path } shouldContain "inbox/killed.pdf"
            killed.uploadedDocuments shouldBe 0
            cluster.remote.exists("inbox/killed.pdf").shouldBeTrue()

            cluster.remote.landThenFailUploads.clear()
            cluster.converge()

            // The copy is identified by its download rather than uploaded again; every replica
            // holds exactly one document and the remote exactly one copy of it.
            cluster.remote.paths().filter { it.endsWith(".pdf") } shouldContainExactlyInAnyOrder
                listOf("inbox/killed.pdf")
            for (rig in listOf(cluster.a, cluster.b)) {
                rig.accountPdfs() shouldHaveSize 1
                rig.exists("inbox/killed.pdf").shouldBeTrue()
            }
        }

    @Test
    fun aTruncatedUploadIsKeptAsAForeignCopyAndTheDocumentSurvives() =
        withCluster { cluster ->
            val doc = cluster.a.importPdf("cut.pdf", "cut-on-the-wire")
            cluster.remote.failUploads += "inbox/cut.pdf"

            val cut = cluster.a.session.syncCompleted()
            cut.errors.map { it.path } shouldContain "inbox/cut.pdf"

            cluster.remote.failUploads.clear()
            cluster.converge()

            // A backend that writes in place left half a PDF at the final name. The next pass cannot
            // tell those bytes from a foreign document, so it keeps them as a copy and the library's
            // own document goes up beside it (a known limit in docs/SYNC_QUALIFICATION.md). What
            // must hold is that the document itself is never lost or overwritten.
            cluster.remote.paths().filter { it.endsWith(".pdf") } shouldHaveSize 2
            for (rig in listOf(cluster.a, cluster.b)) {
                rig.fs.sha256("inbox/cut (2).pdf") shouldBe doc
                rig.fs.sha256("inbox/cut.pdf") shouldNotBe doc
                rig.exists("inbox/cut.pdf").shouldBeTrue()
            }
        }

    @Test
    fun aMultiMegabyteDocumentTravelsIntact() =
        withCluster { cluster ->
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            val big = "%PDF-1.4\n".encodeToByteArray() + bytes + "\n%%EOF\n".encodeToByteArray()
            val docId =
                (
                    cluster.a.session.importPdfBytes(big, "scan.pdf") as ImportOutcome.Imported
                ).docId

            cluster.converge()

            cluster.remote
                .bytes("inbox/scan.pdf")
                .size shouldBe big.size
            cluster.b.fs
                .sha256("inbox/scan.pdf") shouldBe docId
            (cluster.a.session.importPdfBytes(big, "scan.pdf") as ImportOutcome.Duplicate)
                .existingDocId shouldBe docId
            cluster.converge() shouldBe 1
        }

    @Test
    fun aThirdReplicaJoiningGetsEverythingWithoutPushingAnythingBack() =
        withCluster { cluster ->
            val renamed = cluster.a.importPdf("before.pdf", "third-renamed")
            val tagged = cluster.a.importPdf("tagged.pdf", "third-tagged")
            val trashed = cluster.a.importPdf("trashed.pdf", "third-trashed")
            val deleted = cluster.a.importPdf("deleted.pdf", "third-deleted")
            cluster.converge()

            cluster.a.session.docLifecycle
                .renameFile(renamed, "after.pdf")
            cluster.a.session.meta
                .addTag(listOf(tagged), "third")
            cluster.a.session.meta
                .setField(tagged, FieldKeys.CORRESPONDENT, "Initech")
            cluster.a.session.meta
                .addTag(listOf(deleted), "gone")
            cluster.a.session.docLifecycle
                .trashDoc(trashed)
            cluster.a.session.docLifecycle
                .trashDoc(deleted)
            cluster.a.session.docLifecycle
                .deleteDocPermanently(deleted)
            cluster.converge()

            val third = cluster.join("install-c")
            // A join downloads; it pushes no document of its own (only its settings metadata).
            third.session.syncCompleted().uploadedDocuments shouldBe 0
            cluster.converge(members = listOf(cluster.a, cluster.b, third))

            third.exists("inbox/after.pdf").shouldBeTrue()
            third.exists("inbox/before.pdf").shouldBeFalse()
            third.exists("inbox/tagged.pdf").shouldBeTrue()
            third.db
                .documentFieldDao()
                .valuesOf(tagged)[FieldKeys.CORRESPONDENT] shouldBe "Initech"
            third.db
                .tagDao()
                .tagsForDocument(tagged) shouldContain "third"
            third.exists("inbox/trashed.pdf").shouldBeFalse()
            third.db
                .documentDao()
                .getByDocId(trashed)!!
                .trashed
                .shouldBeTrue()
            third.trashDocHashes() shouldContain trashed
            third.exists("inbox/deleted.pdf").shouldBeFalse()
            // A deleted document is never installed on a device that never held it, and the fold
            // synthesizes no row for a deleted one: the tombstone has nothing to list here.
            third.db
                .documentDao()
                .getByDocId(deleted)
                ?.deleted shouldNotBe false

            // The join neither duplicated a document nor resurrected a deleted one, and the
            // replicas are quiet afterwards.
            cluster.remote
                .paths()
                .filter { it.endsWith(".pdf") && !it.startsWith("${NpnPaths.TRASH_DIR}/") }
                .shouldContainExactlyInAnyOrder(listOf("inbox/after.pdf", "inbox/tagged.pdf"))
            cluster.converge() shouldBe 1
        }

    @Test
    fun aRestoredDeviceRebuildsTheLiveLibraryAndKeepsTheTrashAndTombstones() =
        withCluster { cluster ->
            val live = cluster.a.importPdf("live.pdf", "restore-live")
            val renamed = cluster.a.importPdf("old-name.pdf", "restore-renamed")
            val trashed = cluster.a.importPdf("trash-can.pdf", "restore-trashed")
            val deleted = cluster.a.importPdf("deleted.pdf", "restore-deleted")
            cluster.converge()

            cluster.a.session.docLifecycle
                .renameFile(renamed, "new-name.pdf")
            cluster.a.session.meta
                .addTag(listOf(live), "keep-me")
            cluster.a.session.meta
                .setField(live, FieldKeys.TITLE, "A live document")
            cluster.a.session.meta
                .addTag(listOf(deleted), "gone")
            cluster.a.session.docLifecycle
                .trashDoc(trashed)
            cluster.a.session.docLifecycle
                .trashDoc(deleted)
            cluster.a.session.docLifecycle
                .deleteDocPermanently(deleted)
            cluster.converge()

            // The laptop dies: its library folder and databases are gone. A new install opens an
            // empty library, keeps only what it can rebuild from the remote, and connects it.
            val old = cluster.a
            val b = cluster.b
            val oldDeviceId =
                old.deviceDb
                    .keyValueDao()
                    .get(DeviceIdentity.KEY_DEVICE_ID)
                    .shouldNotBeNull()
            cluster.retire(old)
            val restored = cluster.join("restored-laptop")
            // A restore downloads; it pushes no document of its own.
            restored.session.syncCompleted().uploadedDocuments shouldBe 0
            cluster.converge(members = listOf(b, restored))

            restored.exists("inbox/live.pdf").shouldBeTrue()
            restored.fs.sha256("inbox/live.pdf") shouldBe live
            restored.db
                .documentFieldDao()
                .valuesOf(live)[FieldKeys.TITLE] shouldBe "A live document"
            restored.db
                .tagDao()
                .tagsForDocument(live) shouldContain "keep-me"
            restored.exists("inbox/new-name.pdf").shouldBeTrue()
            restored.exists("inbox/old-name.pdf").shouldBeFalse()
            restored.db
                .documentDao()
                .getByDocId(renamed)!!
                .relPath shouldBe "inbox/new-name.pdf"
            restored.exists("inbox/trash-can.pdf").shouldBeFalse()
            restored.db
                .documentDao()
                .getByDocId(trashed)!!
                .trashed
                .shouldBeTrue()
            restored.trashDocHashes() shouldContain trashed
            restored.db
                .documentDao()
                .getByDocId(deleted)
                ?.deleted shouldNotBe false
            restored.exists("inbox/deleted.pdf").shouldBeFalse()
            // A restored installation gets a fresh device id rather than resurrecting the old one.
            // A fresh install gets a fresh device id rather than resurrecting the old one.
            restored.deviceDb
                .keyValueDao()
                .get(DeviceIdentity.KEY_DEVICE_ID)
                .shouldNotBeNull() shouldNotBe oldDeviceId
            cluster.remote
                .paths()
                .filter { it.endsWith(".pdf") && !it.startsWith("${NpnPaths.TRASH_DIR}/") }
                .shouldContainExactlyInAnyOrder(listOf("inbox/live.pdf", "inbox/new-name.pdf"))
            cluster.converge(members = listOf(b, restored)) shouldBe 1
        }

    @Test
    fun aRenameSwapKeepsBothDocumentsButTheirPathsStayApart() =
        withCluster { cluster ->
            val first = cluster.a.importPdf("a.pdf", "swap-first")
            val second = cluster.a.importPdf("b.pdf", "swap-second")
            cluster.converge()

            // Swap the two names on both devices while they are apart. A rename onto an occupied
            // name is refused, so each device goes through a temporary name.
            for (rig in listOf(cluster.a, cluster.b)) {
                rig.session.docLifecycle.renameFile(first, "tmp.pdf")
                rig.session.docLifecycle.renameFile(second, "a.pdf")
                rig.session.docLifecycle.renameFile(first, "b.pdf")
            }
            cluster.converge()

            // Known limit (ARCHITECTURE.md 5.6): renames settle one at a time and each waits while
            // its name is taken, so two documents that swapped names stay apart rather than trade
            // places. Neither is lost, and the remote keeps the names it had.
            for (rig in listOf(cluster.a, cluster.b)) {
                rig.fs.sha256("inbox/a.pdf") shouldBe second
                rig.fs.sha256("inbox/b.pdf") shouldBe first
                rig.db
                    .documentDao()
                    .getByDocId(first)!!
                    .relPath shouldBe "inbox/b.pdf"
                rig.db
                    .documentDao()
                    .getByDocId(second)!!
                    .relPath shouldBe "inbox/a.pdf"
                rig.accountPdfs() shouldHaveSize 2
            }
            cluster.remote.paths().filter { it.endsWith(".pdf") } shouldContainExactlyInAnyOrder
                listOf("inbox/a.pdf", "inbox/b.pdf")
            // The remote keeps the names (and the bytes) it had; each move waits and is reported.
            cluster.remote.fileBytesEqual("inbox/a.pdf", pdf("swap-first")).shouldBeTrue()
            cluster.remote.fileBytesEqual("inbox/b.pdf", pdf("swap-second")).shouldBeTrue()
            cluster.a.session
                .syncCompleted()
                .conflicts
                .map { it.kind } shouldContain ConflictKind.TARGET_OCCUPIED
            cluster.a.exists("inbox/a.pdf").shouldBeTrue()
            cluster.a.exists("inbox/tmp.pdf").shouldBeFalse()
            cluster.a.exists("inbox/b.pdf").shouldBeTrue()
            cluster.converge() shouldBe 1
        }

    private suspend fun AppSessionRig.trashDocHashes(): List<String> =
        fs
            .listAllUnder(NpnPaths.TRASH_DIR)
            .filter { !it.isDirectory }
            .map { fs.sha256(it.relPath) }

    private suspend fun AppSessionRig.accountPdfs(): List<String> =
        fs
            .listAllIndexable()
            .filter { !it.isDirectory }
            .map { it.relPath }
}
