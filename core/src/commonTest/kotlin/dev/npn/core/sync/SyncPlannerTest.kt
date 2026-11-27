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
        journal: Map<String, JournalDocView> = emptyMap(),
        trashTimes: Map<String, Long?> = emptyMap(),
    ) = LocalSnapshot(
        docs.toList(),
        metadataFiles.associateWith { 10L },
        trashFiles,
        now,
        retention,
        journal,
        trashTimes,
    )

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
    fun manifestIdentityCannotDetectASameSizeOverwriteWithTheSameTimestamp() {
        val cached = known("inbox/a.pdf", docA)
        cached.matches(entry("inbox/a.pdf")) shouldBe true
        cached.matches(entry("inbox/a.pdf", mtime = now)) shouldBe false
        cached.matches(entry("inbox/a.pdf", size = 11)) shouldBe false
    }

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
    fun uploadsAtTheLibrarysNameAndReportsAnOccupiedTarget() {
        // The library's indexer put the file at its journaled path; the remote follows that name.
        val remote = remote(entry("2026/a.pdf"))
        val result =
            plan(local(doc(docA, "2026/a.pdf", intent = "2026/a.pdf")), remote, listOf(known("2026/a.pdf", docB)))
        result.remote
            .filterIsInstance<SyncAction.UploadDocument>()
            .single()
            .remotePath shouldBe "2026/a (aaaa).pdf"
        result.conflicts.single().remotePath shouldBe "2026/a.pdf"
        // The unknown-to-us document at the occupied path is installed locally under its own name.
        result.local shouldContainExactly listOf(SyncAction.PublishDocument(docB, "2026/a.pdf", "2026/a.pdf"))
    }

    @Test
    fun movesTheRemoteCopyTowardTheIntent() {
        // The two last agreed on old/a.pdf; the library's indexer has since moved the file to its
        // journaled path, so the remote follows.
        val remote = remote(entry("old/a.pdf"))
        val manifest = listOf(known("old/a.pdf", docA).copy(localPath = "old/a.pdf"))

        val moved = plan(local(doc(docA, "new/a.pdf", intent = "new/a.pdf")), remote, manifest)
        moved.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, "old/a.pdf", "new/a.pdf", entry("old/a.pdf"), localPath = "new/a.pdf"))
        moved.local.shouldBeEmpty()
    }

    @Test
    fun withoutAnIntentTheSideThatRenamedTheDocumentWins() {
        val remote = remote(entry("old/a.pdf"))

        // Renamed here since the two last agreed: the remote follows.
        val here =
            plan(local(doc(docA, "new/a.pdf")), remote, listOf(known("old/a.pdf", docA).copy(localPath = "x/a.pdf")))
        here.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, "old/a.pdf", "new/a.pdf", entry("old/a.pdf"), localPath = "new/a.pdf"))
        here.local.shouldBeEmpty()

        // Never agreed on: the remote's name is another device's choice, and the library takes it.
        plan(local(doc(docA, "new/a.pdf")), remote, listOf(known("old/a.pdf", docA))).local shouldContainExactly
            listOf(SyncAction.MoveLocal(docA, "new/a.pdf", "old/a.pdf", "old/a.pdf"))

        // Still where it was when they last agreed: the remote renamed it, and the library follows.
        val there =
            plan(
                local(doc(docA, "new/a.pdf")),
                remote,
                listOf(known("old/a.pdf", docA).copy(localPath = "new/a.pdf")),
            )
        there.local shouldContainExactly listOf(SyncAction.MoveLocal(docA, "new/a.pdf", "old/a.pdf", "old/a.pdf"))
        there.remote.shouldBeEmpty()
    }

    @Test
    fun aJournaledPathKeepsTheLibrarysNameUntilItsFileMoves() {
        // Another replica moved the document to its journaled path. The indexer places documents
        // itself, so this library keeps its own name and moves nothing: it only remembers the two
        // names as agreed, so neither side undoes the other later.
        val remote = remote(entry("2026/a.pdf"))
        val manifest = listOf(known("2026/a.pdf", docA))
        val moving = plan(local(doc(docA, "inbox/a.pdf", intent = "2026/a.pdf")), remote, manifest)
        moving.remote.shouldBeEmpty()
        moving.local shouldContainExactly listOf(SyncAction.RecordLocalPath(docA, "2026/a.pdf", "inbox/a.pdf"))

        // Once agreed, the remote moves only when this library's file does.
        val agreed = listOf(known("2026/a.pdf", docA).copy(localPath = "inbox/a.pdf"))
        plan(local(doc(docA, "inbox/a.pdf", intent = "2026/a.pdf")), remote, agreed).isEmpty shouldBe true
        plan(local(doc(docA, "2026/b.pdf", intent = "2026/b.pdf")), remote, agreed).remote shouldContainExactly
            listOf(
                SyncAction.MoveRemote(docA, "2026/a.pdf", "2026/b.pdf", entry("2026/a.pdf"), localPath = "2026/b.pdf"),
            )
    }

    @Test
    fun aNameMadeUniqueOnTheRemoteIsTheLibrarysNameToo() {
        // Two devices imported other documents as a.pdf: the second one's copy goes up named for its
        // bytes, `a (bbbb).pdf`, and its library file takes that name before the first one's is installed.
        val remote = remote(entry("a.pdf"))
        val result = plan(local(doc(docB, "a.pdf")), remote, listOf(known("a.pdf", docA)))
        result.local shouldContainExactly
            listOf(
                SyncAction.MoveLocal(docB, "a.pdf", "a (bbbb).pdf", "a (bbbb).pdf"),
                SyncAction.PublishDocument(docA, "a.pdf", "a.pdf"),
            )
        result.remote shouldContainExactly
            listOf(SyncAction.UploadDocument(docB, "a (bbbb).pdf", "a (bbbb).pdf", formerRelPath = "a.pdf"))
        result.conflicts.single().kind shouldBe ConflictKind.UPLOADED_AS_COPY
    }

    @Test
    fun pathsCollideIgnoringCase() {
        val remote = remote(entry("Inbox/A.pdf"))
        val result =
            plan(local(doc(docB, "inbox/a.pdf", intent = "inbox/a.pdf")), remote, listOf(known("Inbox/A.pdf", docA)))
        result.remote shouldContainExactly listOf(SyncAction.UploadDocument(docB, "inbox/a.pdf", "inbox/a (bbbb).pdf"))

        // A rename that only changes case is a move of the document onto its own name.
        val agreed = known("Inbox/A.pdf", docA).copy(localPath = "Inbox/A.pdf")
        val renamed = plan(local(doc(docA, "inbox/a.pdf", intent = "inbox/a.pdf")), remote, listOf(agreed))
        renamed.remote shouldContainExactly
            listOf(
                SyncAction.MoveRemote(
                    docA,
                    "Inbox/A.pdf",
                    "inbox/a.pdf",
                    entry("Inbox/A.pdf"),
                    localPath = "inbox/a.pdf",
                ),
            )
    }

    @Test
    fun aDocumentTheJournalHidesIsNeverInstalledFromTheRemote() {
        val remote = remote(entry("a.pdf"), entry("b.pdf"))
        val manifest = listOf(known("a.pdf", docA), known("b.pdf", docB))
        val journal =
            mapOf(
                docA to JournalDocView(intendedPath = null, deleted = true, trashed = false),
                docB to JournalDocView(intendedPath = null, deleted = false, trashed = true),
            )
        val result = plan(local(journal = journal), remote, manifest)
        result.local.shouldBeEmpty()
    }

    @Test
    fun bytesARelinkSupersededLeaveTheRemoteAndFreeTheNameForTheirSuccessor() {
        val remote = remote(entry("x.pdf"))
        val manifest = listOf(known("x.pdf", docA))
        val journal =
            mapOf(
                docA to JournalDocView(intendedPath = null, deleted = false, trashed = false, supersededBy = docB),
                docB to JournalDocView(intendedPath = null, deleted = false, trashed = false),
            )
        val result = plan(local(doc(docB, "x.pdf"), journal = journal), remote, manifest)
        // The original goes first, so the baked document's upload finds its name free.
        result.remote shouldContainExactly
            listOf(
                SyncAction.DeleteRemote(docA, "x.pdf", entry("x.pdf")),
                SyncAction.UploadDocument(docB, "x.pdf", "x.pdf"),
            )
        result.local.shouldBeEmpty()
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun aDeletedDocumentAnImportBroughtBackUnrecordedStaysOnThisDevice() {
        val journal = mapOf(docA to JournalDocView(intendedPath = null, deleted = true, trashed = false))
        val result = plan(local(doc(docA, "a.pdf"), journal = journal), remote())
        result.remote.shouldBeEmpty()
        result.conflicts.single().kind shouldBe ConflictKind.KEPT_ON_THIS_DEVICE
    }

    @Test
    fun aRowThatLagsTheJournalMovesNothingOnTheRemote() {
        val remote = remote(entry("a.pdf"))
        val manifest = listOf(known("a.pdf", docA))
        val live = mapOf(docA to JournalDocView(intendedPath = null, deleted = false, trashed = false))
        plan(local(doc(docA, "a.pdf", deleted = true, fileAtRelPath = false), journal = live), remote, manifest)
            .remote
            .shouldBeEmpty()
        plan(local(doc(docA, "a.pdf", trashed = true), journal = live), remote, manifest)
            .remote
            .shouldBeEmpty()
    }

    /**
     * Another device trashed a document whose file is still here: the rescan shows it live until
     * the restore settle window has passed, and this pass, the sync carrying the trash, parks the
     * file. Left alone, the rescan would record the file's return once the window passed.
     */
    @Test
    fun aDocumentTheJournalTrashedIsParkedWhileItsRowStillShowsIt() {
        val journal = mapOf(docA to JournalDocView(intendedPath = null, deleted = false, trashed = true))
        val result = plan(local(doc(docA, "a.pdf"), journal = journal, trashTimes = mapOf(docA to trashedAt)), remote())
        result.local shouldContainExactly listOf(SyncAction.MoveLocalToTrash(docA, "a.pdf", trashedAt))
        result.remote.shouldBeEmpty()
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun metadataOfAnotherSizeOnTheRemoteIsUploadedAgain() {
        val truncated = RemoteEntry(objectPath, size = 3, mtimeMillis = now)
        plan(local(metadataFiles = setOf(objectPath)), remote(truncated)).pushMetadata shouldContainExactly
            listOf(SyncAction.UploadObject(objectPath))
    }

    @Test
    fun anOccupiedIntentIsAConflictNotAMove() {
        val remote = remote(entry("old/a.pdf"), entry("new/a.pdf"))
        val manifest = listOf(known("old/a.pdf", docA).copy(localPath = "old/a.pdf"), known("new/a.pdf", docB))
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
        restored.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, trashA, "a.pdf", entry(trashA), localPath = "a.pdf"))
    }

    @Test
    fun anUnidentifiedRemoteFileAtTheUploadTargetIsAConflictNotAnUpload() {
        val remote = remote(entry("inbox/a.pdf"))
        val result = plan(local(doc(docA, "inbox/a.pdf")), remote, listOf(known("inbox/a.pdf", null)))
        result.remote.filterIsInstance<SyncAction.UploadDocument>().shouldBeEmpty()
        result.remote.shouldBeEmpty()
        result.local.shouldBeEmpty()
        result.conflicts shouldContainExactly
            listOf(SyncConflict(docA, "inbox/a.pdf", ConflictKind.UNIDENTIFIED))

        // Without any manifest row at all the outcome is the same: nothing uniquified beside it.
        val bare = plan(local(doc(docA, "inbox/a.pdf")), remote)
        bare.remote.shouldBeEmpty()
        bare.conflicts.single().docId shouldBe docA
    }

    @Test
    fun uniquifiesExtensionLessNamesDottedDirectoriesAndRepeatedCollisions() {
        // Two documents heading for the same occupied path in one pass (names that differ only in
        // case, which the remote folds) are each named for their bytes; two whose hashes start alike
        // get one name, then (2).
        val docC = "c".repeat(64)
        val occupied = remote(entry("a.pdf"))
        val twice =
            plan(
                local(doc(docA, "a.pdf", intent = "a.pdf"), doc(docB, "A.pdf", intent = "A.pdf")),
                occupied,
                listOf(known("a.pdf", docC)),
            )
        twice.remote.filterIsInstance<SyncAction.UploadDocument>().map { it.remotePath } shouldContainExactly
            listOf("a (aaaa).pdf", "A (bbbb).pdf")
        twice.conflicts shouldHaveSize 2
        val alike = "a".repeat(8) + "c".repeat(56)
        val sameToken =
            plan(
                local(doc(docA, "a.pdf", intent = "a.pdf"), doc(alike, "A.pdf", intent = "A.pdf")),
                occupied,
                listOf(known("a.pdf", docC)),
            )
        sameToken.remote.filterIsInstance<SyncAction.UploadDocument>().map { it.remotePath } shouldContainExactly
            listOf("a (aaaa).pdf", "A (aaaa) (2).pdf")

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
            plan(local(doc(docA, "2026/a.pdf", intent = "2026/a.pdf")), remote, listOf(known("2026/a.pdf", docB)))
        upload.conflicts shouldHaveSize 1
        upload.remote.none { it.touches("2026/a.pdf") } shouldBe true

        // Occupied intent for a document the remote already holds elsewhere.
        val both = remote(entry("old/a.pdf"), entry("new/a.pdf"))
        val move =
            plan(
                local(doc(docA, "new/a.pdf", intent = "new/a.pdf")),
                both,
                listOf(known("old/a.pdf", docA).copy(localPath = "old/a.pdf"), known("new/a.pdf", docB)),
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
        result.remote shouldContainExactly
            listOf(SyncAction.MoveRemote(docA, trashA, "a.pdf", entry(trashA), localPath = "a.pdf"))
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
        val stale = ".npn/trash/d__dddddddd-${now - 60 * day}.pdf"
        val result = plan(local(trashFiles = mapOf(stale to TrashFile(now - 60 * day, 10))), remote())
        result.remote.shouldBeEmpty()

        // With retention off, age is no reason to withhold it.
        val noRetention =
            plan(local(trashFiles = mapOf(stale to TrashFile(now - 60 * day, 10)), retention = null), remote())
        noRetention.remote shouldContainExactly listOf(SyncAction.UploadTrash(stale, null))
    }

    @Test
    fun aTrashEntryOfUnknownAgeIsKeptAndTransferred() {
        // An older build's name carries no episode, and its mtime is the PDF's own: nothing says
        // it is old, so it is neither purged nor withheld.
        val unknownAge = entry(".npn/trash/z__abcdef01.pdf", mtime = 0)
        val result = plan(local(), remote(unknownAge))
        result.remote.filterIsInstance<SyncAction.DeleteRemote>().shouldBeEmpty()
        result.local shouldContainExactly listOf(SyncAction.DownloadTrash(unknownAge))
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
        // The stale entry belongs to a deleted document: not fetched. Without an episode in their
        // names, neither local entry is known to be old, so both go up; nothing is purged by mtime.
        result.local shouldContainExactly listOf(SyncAction.DownloadTrash(freshEntry))
        result.remote shouldContainExactlyInAnyOrder
            listOf(
                SyncAction.UploadTrash(".npn/trash/c__cccccccc.pdf", null),
                SyncAction.UploadTrash(".npn/trash/d__dddddddd.pdf", null),
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
    fun theRetentionPurgeGoesByTheJournalsTrashTimesNotTheMtime() {
        // A move into trash keeps the PDF's own mtime: a document imported long ago and trashed
        // yesterday must not be purged, nor one trashed long ago kept for a recent mtime.
        val trashedYesterday = entry(trashA, mtime = now - 400 * day)
        val trashedLongAgo = entry(".npn/trash/b__bbbbbbbb-${now - 40 * day}.pdf", mtime = now - day)
        val manifest = listOf(known(trashedLongAgo.path, docB, ManifestKind.TRASH, mtime = now - day))
        val result =
            plan(
                local(trashTimes = mapOf(docA to now - day, docB to now - 40 * day)),
                remote(trashedYesterday, trashedLongAgo),
                manifest,
            )
        result.remote shouldContainExactly listOf(SyncAction.DeleteRemote(docB, trashedLongAgo.path, trashedLongAgo))
        result.local shouldContainExactly listOf(SyncAction.DownloadTrash(trashedYesterday))
    }

    @Test
    fun anOldRemoteTrashEntryStaysUnlessEveryDocumentItCouldBeLeftLongAgo() {
        val old = entry(".npn/trash/b__bbbbbbbb-${now - 40 * day}.pdf", mtime = now - day)
        val identified = listOf(known(old.path, docB, ManifestKind.TRASH, mtime = now - day))

        fun purges(
            trashTimes: Map<String, Long?>,
            manifest: List<ManifestEntry> = identified,
        ) = plan(local(trashTimes = trashTimes), remote(old), manifest)
            .remote
            .filterIsInstance<SyncAction.DeleteRemote>()
            .isNotEmpty()

        purges(mapOf(docB to now - 40 * day)) shouldBe true
        // No document this device knows: kept, however old the name says it is.
        purges(emptyMap()) shouldBe false
        // Its document is live again (restored), or another document shares the prefix and is live.
        purges(mapOf(docB to null)) shouldBe false
        purges(mapOf(docB to now - 40 * day, "bbbbbbbb" + "c".repeat(56) to null)) shouldBe false
        // Not identified as one of them: kept.
        purges(mapOf(docB to now - 40 * day), manifest = emptyList()) shouldBe false
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
            plan(
                local(doc(docA, "a.pdf"), metadataFiles = setOf(objectPath)),
                remote,
                listOf(known("a.pdf", docA).copy(localPath = "a.pdf")),
            )
        result.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
    }
}
