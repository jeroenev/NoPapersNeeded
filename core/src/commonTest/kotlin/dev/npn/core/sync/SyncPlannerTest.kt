package dev.npn.core.sync

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class SyncPlannerTest {
    private val docA = "a".repeat(64)
    private val docB = "b".repeat(64)
    private val objectHash = "c".repeat(64)
    private val objectPath = ".npn/journal/objects-v2-cc/$objectHash.jsonl"
    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    /** When a trashed document of these tests was trashed: the episode its trash entry is named for. */
    private val trashedAt = now - day
    private val trashA = ".npn/trash/a__aaaaaaaa-$trashedAt.pdf"

    private fun doc(
        id: String,
        relPath: String,
        intent: String? = null,
        trashed: Boolean = false,
        deleted: Boolean = false,
        fileAtRelPath: Boolean = true,
        trashEntry: String? = null,
        size: Long = 10,
    ) = LocalDocument(
        docId = id,
        relPath = relPath,
        size = size,
        trashed = trashed,
        trashedAt = trashedAt.takeIf { trashed },
        deleted = deleted,
        intent = intent,
        fileAtRelPath = fileAtRelPath,
        trashEntry = trashEntry,
    )

    private fun local(
        vararg docs: LocalDocument,
        metadataFiles: Set<String> = emptySet(),
        trashFiles: Map<String, TrashFile> = emptyMap(),
        retention: Long? = 30 * day,
    ) = LocalSnapshot(docs.toList(), metadataFiles, trashFiles, now, retention)

    private fun remote(vararg entries: RemoteEntry) = RemoteIndex(entries.toList())

    private fun entry(
        path: String,
        size: Long = 10,
        mtime: Long = now - day,
    ) = RemoteEntry(path, size, mtime)

    private fun known(
        path: String,
        docId: String?,
        kind: ManifestKind = ManifestKind.DOCUMENT,
        size: Long = 10,
        mtime: Long = now - day,
    ) = ManifestEntry(path, kind, size, mtime, docId)

    private fun plan(
        local: LocalSnapshot,
        remote: RemoteIndex,
        manifest: List<ManifestEntry> = emptyList(),
    ) = SyncPlanner(local, remote, manifest).plan()

    // ---- metadata ---------------------------------------------------------------------------

    @Test
    fun metadataIsASetUnion() {
        val otherPath = ".npn/journal/objects-v2-ee/${"e".repeat(64)}.jsonl"
        val sidecar = ".npn/ocr/$docA.dev.json"
        val localSidecar = ".npn/ocr/$docB.dev.json"
        val remote = remote(entry(objectPath), entry(sidecar), entry(otherPath))
        val localFiles = setOf(otherPath, localSidecar)

        val pull = SyncPlanner.metadataPull(remote, localFiles).map { it.entry.path }
        pull shouldContainExactlyInAnyOrder listOf(objectPath, sidecar)

        val snapshot = local(metadataFiles = localFiles)
        plan(snapshot, remote).pushMetadata.map { it.relPath } shouldContainExactly listOf(localSidecar)
    }

    // ---- live documents -------------------------------------------------------------------

    @Test
    fun uploadsALiveDocumentTheRemoteLacks() {
        val result = plan(local(doc(docA, "inbox/a.pdf")), remote())
        result.remote shouldContainExactly listOf(SyncAction.UploadDocument(docA, "inbox/a.pdf", "inbox/a.pdf"))
        result.local.shouldBeEmpty()
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun uploadsAtTheJournaledIntentAndReportsAnOccupiedTarget() {
        val remote = remote(entry("2026/a.pdf"))
        val result =
            plan(local(doc(docA, "inbox/a.pdf", intent = "2026/a.pdf")), remote, listOf(known("2026/a.pdf", docB)))
        result.remote
            .filterIsInstance<SyncAction.UploadDocument>()
            .single()
            .remotePath shouldBe "2026/a (2).pdf"
        result.conflicts.single().remotePath shouldBe "2026/a.pdf"
        // The unknown-to-us document at the occupied path is installed locally under its own name.
        result.local shouldContainExactly listOf(SyncAction.PublishDocument(docB, "2026/a.pdf", "2026/a.pdf"))
    }

    @Test
    fun movesTheRemoteCopyTowardTheIntentOnlyWhenOneIsJournaled() {
        val remote = remote(entry("old/a.pdf"))
        val manifest = listOf(known("old/a.pdf", docA))

        plan(local(doc(docA, "new/a.pdf")), remote, manifest).remote.shouldBeEmpty()

        val moved = plan(local(doc(docA, "new/a.pdf", intent = "new/a.pdf")), remote, manifest)
        moved.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, "old/a.pdf", "new/a.pdf", entry("old/a.pdf")))
    }

    @Test
    fun anOccupiedIntentIsAConflictNotAMove() {
        val remote = remote(entry("old/a.pdf"), entry("new/a.pdf"))
        val manifest = listOf(known("old/a.pdf", docA), known("new/a.pdf", docB))
        val result = plan(local(doc(docA, "new/a.pdf", intent = "new/a.pdf")), remote, manifest)
        result.remote.filterIsInstance<SyncAction.MoveRemote>().shouldBeEmpty()
        result.conflicts.single().docId shouldBe docA
    }

    @Test
    fun deletesVerifiedRemoteDuplicatesOnlyBehindAnIntent() {
        val remote = remote(entry("a.pdf"), entry("copy/a.pdf"))
        val manifest = listOf(known("a.pdf", docA), known("copy/a.pdf", docA))

        plan(local(doc(docA, "a.pdf")), remote, manifest).remote.shouldBeEmpty()

        val result = plan(local(doc(docA, "a.pdf", intent = "a.pdf")), remote, manifest)
        result.remote shouldContainExactly listOf(SyncAction.DeleteRemote(docA, "copy/a.pdf", entry("copy/a.pdf")))
    }

    @Test
    fun recoversAMissingFileFromTheLocalTrashBeforeTheRemote() {
        val remote = remote(entry("a.pdf"))
        val manifest = listOf(known("a.pdf", docA))

        val fromTrash = plan(local(doc(docA, "a.pdf", fileAtRelPath = false, trashEntry = trashA)), remote, manifest)
        fromTrash.local shouldContainExactly listOf(SyncAction.RestoreLocalTrash(docA, trashA, "a.pdf"))

        val fromRemote = plan(local(doc(docA, "a.pdf", fileAtRelPath = false)), remote, manifest)
        fromRemote.local shouldContainExactly listOf(SyncAction.PublishDocument(docA, "a.pdf", "a.pdf"))
        fromRemote.remote.shouldBeEmpty()
    }

    @Test
    fun aMissingSentinelRowRecoversAtTheRemoteName() {
        val remote = remote(entry("somewhere/a.pdf"))
        val sentinel = doc(docA, ".npn/missing/$docA", fileAtRelPath = false)
        val result = plan(local(sentinel), remote, listOf(known("somewhere/a.pdf", docA)))
        result.local shouldContainExactly listOf(SyncAction.PublishDocument(docA, "somewhere/a.pdf", "somewhere/a.pdf"))
    }

    @Test
    fun installsDocumentsOnlyTheRemoteKnowsOncePerIdentity() {
        val remote = remote(entry("x.pdf"), entry("dup/x.pdf"), entry("unresolved.pdf"))
        val manifest = listOf(known("x.pdf", docB), known("dup/x.pdf", docB), known("unresolved.pdf", null))
        // The alphabetically first remote copy is the one installed, whatever order the manifest came in.
        plan(local(), remote, manifest).local shouldContainExactly
            listOf(SyncAction.PublishDocument(docB, "dup/x.pdf", "dup/x.pdf"))
    }

    @Test
    fun aReverseOrderedManifestStillPicksTheAlphabeticallyFirstCopy() {
        // Unknown document with two remote copies: the first path is installed.
        val remote = remote(entry("z/x.pdf"), entry("m/x.pdf"))
        val reversed = listOf(known("z/x.pdf", docB), known("m/x.pdf", docB))
        plan(local(), remote, reversed).local shouldContainExactly
            listOf(SyncAction.PublishDocument(docB, "m/x.pdf", "m/x.pdf"))

        // Restored document with two remote trash copies: the first is the one moved back.
        val secondTrash = ".npn/trash/b__aaaaaaaa.pdf"
        val trashRemote = remote(entry(secondTrash), entry(trashA))
        val reversedTrash =
            listOf(known(secondTrash, docA, ManifestKind.TRASH), known(trashA, docA, ManifestKind.TRASH))
        val restored = plan(local(doc(docA, "a.pdf", intent = "a.pdf")), trashRemote, reversedTrash)
        restored.remote shouldContainExactly listOf(SyncAction.MoveRemote(docA, trashA, "a.pdf", entry(trashA)))
    }

    @Test
    fun anUnidentifiedRemoteFileAtTheUploadTargetIsAConflictNotAnUpload() {
        val remote = remote(entry("inbox/a.pdf"))
        val result = plan(local(doc(docA, "inbox/a.pdf")), remote, listOf(known("inbox/a.pdf", null)))
        result.remote.filterIsInstance<SyncAction.UploadDocument>().shouldBeEmpty()
        result.remote.shouldBeEmpty()
        result.local.shouldBeEmpty()
        result.conflicts shouldContainExactly
            listOf(SyncConflict(docA, "inbox/a.pdf", "Remote file at this path was not identified; not uploaded"))

        // Without any manifest row at all the outcome is the same: nothing uniquified beside it.
        val bare = plan(local(doc(docA, "inbox/a.pdf")), remote)
        bare.remote.shouldBeEmpty()
        bare.conflicts.single().docId shouldBe docA
    }

    @Test
    fun uniquifiesExtensionLessNamesDottedDirectoriesAndRepeatedCollisions() {
        // Two documents heading for the same occupied path in one pass get (2) and (3).
        val docC = "c".repeat(64)
        val occupied = remote(entry("a.pdf"))
        val twice =
            plan(
                local(doc(docA, "a.pdf"), doc(docB, "b.pdf", intent = "a.pdf")),
                occupied,
                listOf(known("a.pdf", docC)),
            )
        twice.remote.filterIsInstance<SyncAction.UploadDocument>().map { it.remotePath } shouldContainExactly
            listOf("a (2).pdf", "a (3).pdf")
        twice.conflicts shouldHaveSize 2

        // An extension-less name whose trash entry is taken: the next episode, at the very end.
        val bare = ".npn/trash/README__aaaaaaaa-$trashedAt"
        val bareRemote = remote(entry("old/a.pdf"), entry(bare))
        val bareDoc = doc(docA, "docs/README", trashed = true, fileAtRelPath = false)
        val bareMoved =
            plan(local(bareDoc), bareRemote, listOf(known("old/a.pdf", docA), known(bare, docB, ManifestKind.TRASH)))
        val nextEpisode = ".npn/trash/README__aaaaaaaa-${trashedAt + 1}"
        bareMoved.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, "old/a.pdf", nextEpisode, entry("old/a.pdf")))
    }

    @Test
    fun aConflictNeverDeletesOrMovesTheOccupyingFile() {
        // Occupied upload target.
        val remote = remote(entry("2026/a.pdf"))
        val upload =
            plan(local(doc(docA, "inbox/a.pdf", intent = "2026/a.pdf")), remote, listOf(known("2026/a.pdf", docB)))
        upload.conflicts shouldHaveSize 1
        upload.remote.none { it.touches("2026/a.pdf") } shouldBe true

        // Occupied intent for a document the remote already holds elsewhere.
        val both = remote(entry("old/a.pdf"), entry("new/a.pdf"))
        val move =
            plan(
                local(doc(docA, "new/a.pdf", intent = "new/a.pdf")),
                both,
                listOf(known("old/a.pdf", docA), known("new/a.pdf", docB)),
            )
        move.conflicts shouldHaveSize 1
        move.remote.none { it.touches("new/a.pdf") } shouldBe true
        move.remote.filterIsInstance<SyncAction.DeleteRemote>().shouldBeEmpty()
    }

    private fun SyncAction.Remote.touches(path: String): Boolean =
        when (this) {
            is SyncAction.DeleteRemote -> this.path == path
            is SyncAction.MoveRemote -> from == path || to == path
            is SyncAction.UploadDocument, is SyncAction.UploadTrash -> false
        }

    @Test
    fun aRestoredDocumentMovesItsRemoteTrashCopyBackInsteadOfUploading() {
        val remote = remote(entry(trashA))
        val result =
            plan(local(doc(docA, "a.pdf", intent = "a.pdf")), remote, listOf(known(trashA, docA, ManifestKind.TRASH)))
        result.remote shouldContainExactly listOf(SyncAction.MoveRemote(docA, trashA, "a.pdf", entry(trashA)))
    }

    // ---- trash ------------------------------------------------------------------------------

    @Test
    fun mirrorsATrashedDocumentOntoTheRemoteAndOntoThisReplica() {
        val remote = remote(entry("a.pdf"))
        val manifest = listOf(known("a.pdf", docA))
        val trashedDoc = doc(docA, "a.pdf", trashed = true, fileAtRelPath = false, trashEntry = trashA)
        val moveToTrash = SyncAction.MoveRemote(docA, "a.pdf", trashA, entry("a.pdf"))

        // The trashing device: its file is already in its trash, the remote still has it live.
        val trasher = plan(local(trashedDoc), remote, manifest)
        trasher.remote shouldContainExactly listOf(moveToTrash)
        trasher.local.shouldBeEmpty()

        // Another replica, after the remote move: its file still sits at the live path.
        val afterMove = remote(entry(trashA))
        val other =
            plan(local(doc(docA, "a.pdf", trashed = true)), afterMove, listOf(known(trashA, docA, ManifestKind.TRASH)))
        other.local shouldContainExactly listOf(SyncAction.MoveLocalToTrash(docA, "a.pdf", trashedAt))
        other.remote.shouldBeEmpty()

        // Same replica while the remote still holds the document live: wait, do not resurrect.
        val waiting = plan(local(doc(docA, "a.pdf", trashed = true)), remote, manifest)
        waiting.local.shouldBeEmpty()
        waiting.remote shouldContainExactly listOf(moveToTrash)
    }

    @Test
    fun aRemoteTrashCopyMakesTheLiveCopyRedundant() {
        val remote = remote(entry("a.pdf"), entry(trashA))
        val manifest = listOf(known("a.pdf", docA), known(trashA, docA, ManifestKind.TRASH))
        val trashedDoc = doc(docA, "a.pdf", trashed = true, fileAtRelPath = false, trashEntry = trashA)
        val result = plan(local(trashedDoc), remote, manifest)
        result.remote shouldContainExactly listOf(SyncAction.DeleteRemote(docA, "a.pdf", entry("a.pdf")))
    }

    @Test
    fun aTrashedDocumentWaitsWhileTheRemoteHoldsItLiveUnderAnotherPath() {
        val remote = remote(entry("other/a.pdf"))
        val manifest = listOf(known("other/a.pdf", docA))
        val result = plan(local(doc(docA, "a.pdf", trashed = true)), remote, manifest)
        result.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, "other/a.pdf", trashA, entry("other/a.pdf")))
        result.local.filterIsInstance<SyncAction.MoveLocalToTrash>().shouldBeEmpty()

        // Once the remote holds only the trash copy, the local file is parked.
        val afterMove =
            plan(
                local(doc(docA, "a.pdf", trashed = true)),
                remote(entry(trashA)),
                listOf(known(trashA, docA, ManifestKind.TRASH)),
            )
        afterMove.local shouldContainExactly listOf(SyncAction.MoveLocalToTrash(docA, "a.pdf", trashedAt))
        afterMove.remote.shouldBeEmpty()
    }

    @Test
    fun severalRemoteCopiesOfATrashedDocumentMoveTheFirstAndDeleteTheRest() {
        val remote = remote(entry("copy/a.pdf"), entry("a.pdf"))
        val manifest = listOf(known("copy/a.pdf", docA), known("a.pdf", docA))
        val trashedDoc = doc(docA, "a.pdf", trashed = true, fileAtRelPath = false, trashEntry = trashA)
        val result = plan(local(trashedDoc), remote, manifest)
        result.remote shouldContainExactly
            listOf(
                SyncAction.MoveRemote(docA, "a.pdf", trashA, entry("a.pdf")),
                SyncAction.DeleteRemote(docA, "copy/a.pdf", entry("copy/a.pdf")),
            )
    }

    @Test
    fun anOccupiedTrashTargetTakesTheNextEpisode() {
        val remote = remote(entry("a.pdf"), entry(trashA))
        val manifest = listOf(known("a.pdf", docA), known(trashA, docB, ManifestKind.TRASH))
        val result = plan(local(doc(docA, "a.pdf", trashed = true, fileAtRelPath = false)), remote, manifest)
        result.remote.filterIsInstance<SyncAction.MoveRemote>() shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, "a.pdf", ".npn/trash/a__aaaaaaaa-${trashedAt + 1}.pdf", entry("a.pdf")))
        result.remote.none { it is SyncAction.DeleteRemote && it.path == trashA } shouldBe true
    }

    @Test
    fun aFreshTrashEntryForADeletedDocumentIsNotDownloaded() {
        val fresh = entry(".npn/trash/b__bbbbbbbb.pdf", mtime = now - day)
        val result = plan(local(doc(docB, "b.pdf", deleted = true, fileAtRelPath = false)), remote(fresh))
        result.local.filterIsInstance<SyncAction.DownloadTrash>().shouldBeEmpty()
    }

    @Test
    fun localTrashOlderThanRetentionIsNotUploaded() {
        val stale = ".npn/trash/d__dddddddd.pdf"
        val result = plan(local(trashFiles = mapOf(stale to TrashFile(now - 60 * day, 10))), remote())
        result.remote.shouldBeEmpty()

        // With retention off, age is no reason to withhold it.
        val noRetention =
            plan(local(trashFiles = mapOf(stale to TrashFile(now - 60 * day, 10)), retention = null), remote())
        noRetention.remote shouldContainExactly listOf(SyncAction.UploadTrash(stale, null))
    }

    @Test
    fun theRetentionPurgeSkipsEntriesWithoutAnMtime() {
        val unknownAge = entry(".npn/trash/z__abcdef01.pdf", mtime = 0)
        val result = plan(local(), remote(unknownAge))
        result.remote.filterIsInstance<SyncAction.DeleteRemote>().shouldBeEmpty()
        // Nor is an entry of unknown age fetched: it cannot be shown to be within retention.
        result.local.filterIsInstance<SyncAction.DownloadTrash>().shouldBeEmpty()
    }

    @Test
    fun uploadedTrashHasNoOwnerWhenTwoDocumentsShareTheMarkerAndSize() {
        val docA2 = "a".repeat(8) + "b".repeat(56)
        val snapshot =
            local(
                doc(docA, "a.pdf", trashed = true, fileAtRelPath = false, size = 42),
                doc(docA2, "a2.pdf", trashed = true, fileAtRelPath = false, size = 42),
                trashFiles = mapOf(trashA to TrashFile(now, 42)),
            )
        plan(snapshot, remote()).remote shouldContainExactly listOf(SyncAction.UploadTrash(trashA, null))
    }

    @Test
    fun trashEntriesTravelWithinRetentionAndAreNotFetchedForDeletedOrLiveDocuments() {
        val stale = ".npn/trash/old__bbbbbbbb.pdf"
        val freshEntry = entry(trashA, mtime = now - day)
        val staleEntry = entry(stale, mtime = now - 40 * day)
        val remote = remote(freshEntry, staleEntry)
        val snapshot =
            local(
                doc(docB, "b.pdf", deleted = true, fileAtRelPath = false),
                trashFiles =
                    mapOf(
                        ".npn/trash/c__cccccccc.pdf" to TrashFile(now - day, 10),
                        ".npn/trash/d__dddddddd.pdf" to TrashFile(now - 60 * day, 10),
                    ),
            )
        val result = plan(snapshot, remote)
        result.local shouldContainExactly listOf(SyncAction.DownloadTrash(freshEntry))
        result.remote shouldContainExactlyInAnyOrder
            listOf(
                SyncAction.UploadTrash(".npn/trash/c__cccccccc.pdf", null),
                SyncAction.DeleteRemote(null, stale, staleEntry),
            )

        val liveWithFile = plan(local(doc(docA, "a.pdf")), remote(freshEntry))
        liveWithFile.local.filterIsInstance<SyncAction.DownloadTrash>().shouldBeEmpty()
    }

    @Test
    fun uploadedTrashCarriesTheOwnerWhenMarkerAndSizeIdentifyOne() {
        val snapshot =
            local(
                doc(docA, "a.pdf", trashed = true, fileAtRelPath = false, size = 42),
                trashFiles = mapOf(trashA to TrashFile(now, 42)),
            )
        plan(snapshot, remote()).remote shouldContainExactly listOf(SyncAction.UploadTrash(trashA, docA))
    }

    // ---- deletes ----------------------------------------------------------------------------

    @Test
    fun mirrorsAPermanentDeleteBothWays() {
        val remote = remote(entry("a.pdf"), entry(trashA))
        val manifest = listOf(known("a.pdf", docA), known(trashA, docA, ManifestKind.TRASH))

        val deleter = plan(local(doc(docA, "a.pdf", deleted = true, fileAtRelPath = false)), remote, manifest)
        deleter.remote shouldContainExactlyInAnyOrder
            listOf(
                SyncAction.DeleteRemote(docA, "a.pdf", entry("a.pdf")),
                SyncAction.DeleteRemote(docA, trashA, entry(trashA)),
            )
        deleter.local.shouldBeEmpty()

        // Another replica still holding both copies, once the remote has none.
        val other = plan(local(doc(docA, "a.pdf", deleted = true, trashEntry = trashA)), remote())
        other.local shouldContainExactlyInAnyOrder
            listOf(
                SyncAction.DeleteLocalFile(docA, "a.pdf"),
                SyncAction.DeleteLocalFile(docA, trashA),
            )

        // While the remote still has a live copy, the local file waits.
        val waiting =
            plan(local(doc(docA, "a.pdf", deleted = true)), remote(entry("a.pdf")), listOf(known("a.pdf", docA)))
        waiting.local.shouldBeEmpty()
        waiting.remote shouldHaveSize 1
        waiting.remote.single().shouldBeInstanceOf<SyncAction.DeleteRemote>()
    }

    @Test
    fun aDeletedDocumentsStaleRemoteTrashCopyIsDeletedExactlyOnce() {
        // Both the delete mirror and the retention purge want this file gone; one DeleteRemote results.
        val stalePath = ".npn/trash/a__aaaaaaaa-${now - 40 * day}.pdf"
        val stale = entry(stalePath)
        val result =
            plan(
                local(doc(docA, "a.pdf", deleted = true, fileAtRelPath = false)),
                remote(stale),
                listOf(known(stalePath, docA, ManifestKind.TRASH)),
            )
        result.remote shouldContainExactly listOf(SyncAction.DeleteRemote(docA, stalePath, stale))
    }

    @Test
    fun theRetentionPurgeGoesByTheTrashEpisodeNotTheMtime() {
        // A move into trash keeps the PDF's own mtime: a document imported long ago and trashed
        // yesterday must not be purged, nor one trashed long ago kept for a recent mtime.
        val trashedYesterday = entry(trashA, mtime = now - 400 * day)
        val trashedLongAgo = entry(".npn/trash/b__bbbbbbbb-${now - 40 * day}.pdf", mtime = now - day)
        val result = plan(local(), remote(trashedYesterday, trashedLongAgo))
        result.remote shouldContainExactly listOf(SyncAction.DeleteRemote(null, trashedLongAgo.path, trashedLongAgo))
        result.local shouldContainExactly listOf(SyncAction.DownloadTrash(trashedYesterday))
    }

    @Test
    fun aDeletedDocumentsLocalFileWaitsWhileAnyRemoteCopyIsLive() {
        val remote = remote(entry("elsewhere/a.pdf"))
        val result = plan(local(doc(docA, "a.pdf", deleted = true)), remote, listOf(known("elsewhere/a.pdf", docA)))
        result.local.shouldBeEmpty()
        result.remote shouldContainExactly
            listOf(SyncAction.DeleteRemote(docA, "elsewhere/a.pdf", entry("elsewhere/a.pdf")))
    }

    @Test
    fun aNoOpLibraryPlansNothing() {
        val remote = remote(entry("a.pdf"), entry(objectPath))
        val result =
            plan(local(doc(docA, "a.pdf"), metadataFiles = setOf(objectPath)), remote, listOf(known("a.pdf", docA)))
        result.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
    }
}
