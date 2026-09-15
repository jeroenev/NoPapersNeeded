package dev.npn.core.sync

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SyncPathsTest {
    private val hash = "a".repeat(64)

    @Test
    fun classifiesTheMirroredLayoutLikeTheLibraryDoes() {
        SyncPaths.classify("inbox/scan.pdf") shouldBe SyncPathClass.Document
        SyncPaths.classify("2026/Invoice (2).PDF") shouldBe SyncPathClass.Document
        SyncPaths.classify(".npn/journal/objects-v2-aa/$hash.jsonl") shouldBe
            SyncPathClass.JournalObject(hash, snapshot = false)
        SyncPaths.classify(".npn/journal/objects-snap-v2-aa/$hash.jsonl") shouldBe
            SyncPathClass.JournalObject(hash, snapshot = true)
        SyncPaths.classify(".npn/ocr/$hash.device-1.tesseract.json") shouldBe SyncPathClass.WriteOnce
        SyncPaths.classify(".npn/devices/device-1/install-a.json") shouldBe SyncPathClass.WriteOnce
        SyncPaths.classify(".npn/trash/scan__aaaaaaaa-1700000000000.pdf") shouldBe SyncPathClass.Trash
        SyncPaths.classify(".npn/trash/scan__aaaaaaaa.pdf") shouldBe SyncPathClass.Trash
    }

    @Test
    fun ignoresWhatNeitherSideShouldTransfer() {
        SyncPaths.classify(".npn/journal/objects-v2-aa/.$hash.install.nonce.part") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/journal/objects-v3-aa/$hash.jsonl") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/journal/objects-v2-aa/$hash.sync-conflict-20260101-000000-ABCDEFG.jsonl") shouldBe
            SyncPathClass.Ignored
        SyncPaths.classify(".npn/journal/device-1/0000000000000001.jsonl") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/devices/device-1/.install-a.nonce.part") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/devices/install-a.json") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/tmp/abc.part") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/missing/$hash") shouldBe SyncPathClass.Ignored
        SyncPaths.classify("notes.txt") shouldBe SyncPathClass.Ignored
        SyncPaths.classify("inbox/scan.sync-conflict-20260101-000000-ABCDEFG.pdf") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".hidden/scan.pdf") shouldBe SyncPathClass.Ignored
        SyncPaths.classify("../escape.pdf") shouldBe SyncPathClass.Ignored
        SyncPaths.classify(".npn/ocr/nested/$hash.device.json") shouldBe SyncPathClass.Ignored
    }

    @Test
    fun remoteIndexBucketsEntriesAndKeepsObjectHashes() {
        val index =
            RemoteIndex(
                listOf(
                    RemoteEntry("inbox/a.pdf", 1, 1),
                    RemoteEntry(".npn/trash/a__aaaaaaaa.pdf", 1, 1),
                    RemoteEntry(".npn/journal/objects-v2-aa/$hash.jsonl", 1, 1),
                    RemoteEntry(".npn/ocr/$hash.dev.json", 1, 1),
                    RemoteEntry(".npn/tmp/x.part", 1, 1),
                ),
            )
        index.documents.keys shouldBe setOf("inbox/a.pdf")
        index.trash.keys shouldBe setOf(".npn/trash/a__aaaaaaaa.pdf")
        index.metadata.keys shouldBe setOf(".npn/journal/objects-v2-aa/$hash.jsonl", ".npn/ocr/$hash.dev.json")
        index.objectHashes shouldBe mapOf(".npn/journal/objects-v2-aa/$hash.jsonl" to hash)
        index.has(".npn/tmp/x.part") shouldBe true
        index.has("nope.pdf") shouldBe false
    }
}
