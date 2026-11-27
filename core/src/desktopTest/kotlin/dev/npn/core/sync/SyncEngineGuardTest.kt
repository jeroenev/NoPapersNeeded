package dev.npn.core.sync

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.fs.withNameToken
import dev.npn.core.journal.JournalPaths
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.Test

/**
 * The destructive half of the engine: remote move/delete/upload guards against a remote that
 * changed between listing and acting, the local identity checks before trash and delete, the
 * missing-file and failed-listing rules, retention, and corrupt metadata. Real sessions over a
 * directory-backed remote, like [SyncEngineTest].
 */
internal class SyncEngineGuardTest : SyncEngineTestBase() {
    @Test
    fun aRemoteFileReplacedBetweenListingAndMoveIsLeftAloneAndReported() =
        withPair { pair ->
            val docId = pair.importOnA("draft.pdf", "guard-move")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()
            pair.b.session.docLifecycle
                .renameFile(docId, "final.pdf")

            val foreign = pdf("a replacement of a clearly different length")
            pair.remote.onceBeforeStat("inbox/draft.pdf") { pair.remote.put("inbox/draft.pdf", foreign) }
            val report = pair.b.session.syncCompleted()
            report.movedRemote shouldBe 0
            report.errors.shouldBeEmpty()
            report.conflicts.map { it.kind } shouldContain ConflictKind.REMOTE_CHANGED
            pair.remote.exists("inbox/final.pdf").shouldBeFalse()
            pair.remote.bytes("inbox/draft.pdf") shouldBe foreign
        }

    @Test
    fun aMoveDestinationOccupiedSinceListingIsNotOverwritten() =
        withPair { pair ->
            val docId = pair.importOnA("draft.pdf", "guard-dest")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()
            pair.b.session.docLifecycle
                .renameFile(docId, "final.pdf")

            val foreign = pdf("landed at the destination first")
            pair.remote.onceBeforeStat("inbox/draft.pdf") { pair.remote.put("inbox/final.pdf", foreign) }
            val report = pair.b.session.syncCompleted()
            report.movedRemote shouldBe 0
            report.conflicts.map { it.kind } shouldContain ConflictKind.TARGET_OCCUPIED
            pair.remote.bytes("inbox/final.pdf") shouldBe foreign
            pair.remote.fileBytesEqual("inbox/draft.pdf", pdf("guard-dest")).shouldBeTrue()
        }

    @Test
    fun aRemoteFileChangedSinceListingIsNotDeleted() =
        withPair { pair ->
            val docId = pair.importOnA("gone.pdf", "guard-delete")
            pair.a.session.syncCompleted()
            // Only a trashed document is deleted for good.
            pair.a.session.docLifecycle
                .trashDoc(docId)
            pair.a.session.docLifecycle
                .deleteDocPermanently(docId)

            val foreign = pdf("someone else wrote here in the meantime")
            pair.remote.onceBeforeStat("inbox/gone.pdf") { pair.remote.put("inbox/gone.pdf", foreign) }
            val report = pair.a.session.syncCompleted()
            report.deletedRemote shouldBe 0
            report.conflicts.map { it.kind } shouldContain ConflictKind.REMOTE_CHANGED
            pair.remote.bytes("inbox/gone.pdf") shouldBe foreign
        }

    @Test
    fun pendingCountsKeepThePlanTimeWhenTransfersTakeTime() =
        withPair { pair ->
            pair.importOnA("clock.pdf", "clock")
            var transferAt = 0L
            pair.remote.onceBeforeStat("inbox/clock.pdf") {
                transferAt = pair.a.clock.now
                pair.a.clock.now += 10_000
            }

            val report = pair.a.session.syncCompleted()

            (report.pendingCheckedAt!! <= transferAt).shouldBeTrue()
            (report.startedAt + report.durationMillis >= transferAt + 10_000).shouldBeTrue()
        }

    @Test
    fun aRemotePathThatAppearsAfterPlanningIsNotOverwrittenByAnUpload() =
        withPair { pair ->
            pair.importOnA("x.pdf", "guard-upload")
            val foreign = pdf("dropped in by another device just now")
            pair.remote.onceBeforeStat("inbox/x.pdf") { pair.remote.put("inbox/x.pdf", foreign) }
            val report = pair.a.session.syncCompleted()
            report.uploadedDocuments shouldBe 0
            report.conflicts.map { it.kind } shouldContain ConflictKind.REMOTE_CHANGED
            pair.remote.bytes("inbox/x.pdf") shouldBe foreign
            // Only metadata went up; the lone PDF is the foreign one, untouched.
            pair.remote.paths().filter { it.endsWith(".pdf") } shouldContainExactly listOf("inbox/x.pdf")
        }

    @Test
    fun aLocalFileThatNoLongerHoldsTheDocumentIsNeverTrashedOrDeleted() =
        withPair { pair ->
            val trashedId = pair.importOnA("keep.pdf", "local-trash")
            val deletedId = pair.importOnA("gone.pdf", "local-delete")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()
            pair.a.session.docLifecycle
                .trashDoc(trashedId)
            pair.a.session.docLifecycle
                .trashDoc(deletedId)
            pair.a.session.docLifecycle
                .deleteDocPermanently(deletedId)
            pair.a.session.syncCompleted()

            val keep = pdf("overwritten on B before the mirror")
            val gone = pdf("also overwritten on B")
            pair.b.writePdf("inbox/keep.pdf", keep)
            pair.b.writePdf("inbox/gone.pdf", gone)
            val report = pair.b.session.syncCompleted()
            report.appliedLocally shouldBe 0
            report.errors.shouldBeEmpty()
            report.conflicts.filter {
                it.kind == ConflictKind.LOCAL_CHANGED
            } shouldHaveSize
                2
            Files.readAllBytes(pair.b.libRoot.resolve("inbox/keep.pdf")) shouldBe keep
            Files.readAllBytes(pair.b.libRoot.resolve("inbox/gone.pdf")) shouldBe gone
        }

    /**
     * Another process replaces B's file between the pass's check of it and its move into the trash
     * (an overwrite by rename): the move takes the other file. It goes back to its path, and never
     * stays in the trash under the document's name, where no restore or purge ever takes it (seed
     * 4210027 at 43fbf8e5, "files in the trash hold bytes no document of their name has").
     */
    @Test
    fun aFileReplacedBeforeTheMoveIntoTheTrashGoesBack() {
        var interfere = false
        var rig: AppSessionRig? = null
        val foreign = pdf("replaced by rename after the pass checked it")
        withPair(
            filesOnB = { fs ->
                object : LibraryFileSystem by fs {
                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        if (interfere && fromRelPath == "inbox/keep.pdf" &&
                            toRelPath.startsWith(NpnPaths.TRASH_DIR + "/")
                        ) {
                            interfere = false
                            rig?.writePdf(fromRelPath, foreign)
                        }
                        fs.move(fromRelPath, toRelPath)
                    }
                }
            },
        ) { pair ->
            rig = pair.b
            val docId = pair.importOnA("keep.pdf", "trashed elsewhere")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()
            pair.a.session.docLifecycle
                .trashDoc(docId)
            pair.a.session.syncCompleted()

            interfere = true
            pair.b.session.syncCompleted()
            interfere.shouldBeFalse()
            pair.b.fs
                .trashEntries()
                .filter { pair.b.fs.sha256(it.relPath) != docId }
                .shouldBeEmpty()
            Files.readAllBytes(pair.b.libRoot.resolve("inbox/keep.pdf")) shouldBe foreign
        }
    }

    @Test
    fun aRemoteFileGoneFromTheListingIsUnknownNotDeletedAndIsReUploaded() =
        withPair { pair ->
            val docId = pair.importOnA("stay.pdf", "missing-remote")
            pair.a.session.syncCompleted()
            pair.b.session.syncCompleted()
            val before =
                pair.b.deviceDb
                    .syncRemoteDao()
                    .all()
                    .single { it.remotePath == "inbox/stay.pdf" }

            Files.delete(pair.remote.root.resolve("inbox/stay.pdf"))
            val report = pair.b.session.syncCompleted()
            report.uploadedDocuments shouldBe 1
            report.appliedLocally shouldBe 0
            pair.b.exists("inbox/stay.pdf").shouldBeTrue()
            val row =
                pair.b.db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.deleted.shouldBeFalse()
            row.trashed.shouldBeFalse()
            pair.remote.exists("inbox/stay.pdf").shouldBeTrue()
            // The stale manifest row was pruned with the listing; the one there now is the re-upload's.
            val after =
                pair.b.deviceDb
                    .syncRemoteDao()
                    .all()
                    .single { it.remotePath == "inbox/stay.pdf" }
            after.docId shouldBe docId
            after.syncedAt shouldBeGreaterThan before.syncedAt
        }

    @Test
    fun aFailedListingFailsTheWholePassAndChangesNothing() =
        withPair { pair ->
            pair.importOnA("x.pdf", "fail-list")
            pair.remote.failList = true
            pair.a.session.sync
                .syncNow()
                .shouldBeInstanceOf<SyncOutcome.Failed>()
            pair.a.session.sync.status
                .first()
                .lastRun
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
            pair.remote.paths().shouldBeEmpty()
            pair.remote.uploads shouldBe 0
            pair.a.exists("inbox/x.pdf").shouldBeTrue()
            pair.a.deviceDb
                .syncRemoteDao()
                .all()
                .shouldBeEmpty()

            pair.remote.failList = false
            pair.a.session
                .syncCompleted()
                .uploadedDocuments shouldBe 1
        }

    @Test
    fun aLocalInstallNeverReplacesAnOccupiedPath() =
        withPair { pair ->
            pair.b.session.syncCompleted()
            val mine = pdf("B's own unsynced document")
            val theirs = pdf("a different document under the same name")
            val mineId = (pair.b.session.importPdfBytes(mine, "x.pdf") as ImportOutcome.Imported).docId
            val copy = withNameToken("inbox/x.pdf", mineId)
            pair.remote.put("inbox/x.pdf", theirs)

            val report = pair.b.session.syncCompleted()
            report.errors.shouldBeEmpty()
            // Neither file replaced the other: B's document went up under a free name and took that
            // name here too, so the remote's file installed at its own name, as on every replica.
            Files.readAllBytes(pair.b.libRoot.resolve("inbox/x.pdf")) shouldBe theirs
            Files.readAllBytes(pair.b.libRoot.resolve(copy)) shouldBe mine
            report.conflicts.filter {
                it.kind == ConflictKind.UPLOADED_AS_COPY && it.otherPath == copy
            } shouldHaveSize
                1
            pair.remote.bytes("inbox/x.pdf") shouldBe theirs
            pair.remote.bytes(copy) shouldBe mine
            pair.b.session.ingest
                .rescan()
            pair.b.db
                .documentDao()
                .getByRelPath(copy)
                .shouldNotBeNull()
            pair.b.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
        }

    @Test
    fun aTrashEntryTravelsToAReplicaThatNeverHeldTheDocument() =
        withPair { pair ->
            val docId = pair.importOnA("t.pdf", "trash-travel")
            pair.a.session.docLifecycle
                .trashDoc(docId)
            val trashName =
                soleFileUnder(pair.a.libRoot.resolve(NpnPaths.TRASH_DIR))
                    .shouldNotBeNull()
                    .fileName()
            val trashPath = "${NpnPaths.TRASH_DIR}/$trashName"

            val pushed = pair.a.session.syncCompleted()
            pushed.uploadedDocuments shouldBe 0
            pair.remote.exists(trashPath).shouldBeTrue()
            pair.remote.exists("inbox/t.pdf").shouldBeFalse()
            pair.a.deviceDb
                .syncRemoteDao()
                .all()
                .single { it.remotePath == trashPath }
                .docId shouldBe docId

            val pulled = pair.b.session.syncCompleted()
            pulled.downloadedDocuments shouldBe 0
            pulled.appliedLocally shouldBe 1
            pair.b.exists(trashPath).shouldBeTrue()
            pair.b.exists("inbox/t.pdf").shouldBeFalse()
            pair.b.fs.sha256(trashPath) shouldBe docId
            pair.b.session
                .syncCompleted()
                .changedAnything
                .shouldBeFalse()
        }

    @Test
    fun theRetentionPurgeDeletesOnlyRemoteTrashItsDocumentLeftLongAgo() =
        withPair { pair ->
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            pair.a.clock.now = now - 40 * day
            val docId = pair.importOnA("old.pdf", "trashed forty days ago")
            pair.a.session.syncCompleted()
            pair.a.session.docLifecycle
                .trashDoc(docId)
            pair.a.session.syncCompleted()
            val entry = pair.remote.paths().single { it.startsWith("${NpnPaths.TRASH_DIR}/") }
            // A stray entry no document accounts for, with an old mtime: nothing says whose it is.
            val stray = "${NpnPaths.TRASH_DIR}/stray__0badf00d.pdf"
            pair.remote.put(stray, pdf("stray trash"))
            pair.remote.setMtime(stray, now - 400 * day)

            pair.a.clock.now = now
            val report = pair.a.session.syncCompleted()
            report.errors.shouldBeEmpty()
            report.deletedRemote shouldBe 1
            pair.remote.exists(entry).shouldBeFalse()
            pair.remote.exists(stray).shouldBeTrue()
        }

    @Test
    fun aRemoteObjectThatDoesNotHashToItsNameIsNeverPublished() =
        withPair { pair ->
            val hash = "f".repeat(64)
            val path = JournalPaths.objectPath(hash)
            pair.remote.put(path, "not a journal object".encodeToByteArray())

            val report = pair.b.session.syncCompleted()
            report.downloadedObjects shouldBe 0
            report.errors.map { it.path } shouldContain path
            pair.b.exists(path).shouldBeFalse()
            pair.b.fs
                .listAllUnder(NpnPaths.JOURNAL_DIR)
                .none { it.relPath.contains(hash) }
                .shouldBeTrue()
        }

    @Test
    fun aRemoteDocumentWhoseBytesDoNotMatchTheListingIsRejected() =
        withPair { pair ->
            pair.remote.put("scans/bad.pdf", pdf("tampered in transit"))
            pair.remote.tamperDownloads += "scans/bad.pdf"

            val report = pair.b.session.syncCompleted()
            report.downloadedDocuments shouldBe 0
            report.errors.map { it.path } shouldContain "scans/bad.pdf"
            pair.b.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .shouldBeEmpty()
            pair.b.deviceDb
                .syncRemoteDao()
                .all()
                .none { it.remotePath == "scans/bad.pdf" }
                .shouldBeTrue()

            pair.remote.tamperDownloads.clear()
            val retried = pair.b.session.syncCompleted()
            retried.downloadedDocuments shouldBe 1
            retried.errors.shouldBeEmpty()
            pair.b.exists("scans/bad.pdf").shouldBeTrue()
        }

    @Test
    fun aLocalFileEditedSinceIndexingIsNotUploadedUnderTheOldIdentity() =
        withPair { pair ->
            pair.importOnA("edit.pdf", "before-edit")
            pair.a.writePdf("inbox/edit.pdf", pdf("edited after indexing, before sync"))

            val report = pair.a.session.syncCompleted()
            report.uploadedDocuments shouldBe 0
            report.errors.shouldBeEmpty()
            report.conflicts.map { it.kind } shouldContain ConflictKind.LOCAL_CHANGED
            pair.remote.exists("inbox/edit.pdf").shouldBeFalse()
            pair.remote.uploads shouldBe pair.remote.paths().size // only metadata went up
        }

    @Test
    fun aScratchFileFromAnEarlierPassIsSweptAndNeverBecomesADocument() =
        withPair { pair ->
            val scratchDir =
                pair.b.dbDir
                    .toPath()
                    .resolve("first/sync")
            Files.createDirectories(scratchDir)
            Files.write(scratchDir.resolve("npn-sync-leftover.pdf"), pdf("eleven"))
            pair.b.session.syncCompleted()
            Files.exists(scratchDir.resolve("npn-sync-leftover.pdf")).shouldBeFalse()
            pair.b.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .shouldBeEmpty()
        }
}
