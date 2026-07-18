package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class NpnPathsTest {

    @Test
    fun journalAndOcrPaths() {
        NpnPaths.journalDeviceDir("dev1") shouldBe ".npn/journal/dev1"
        NpnPaths.journalSegmentPath("dev1", "0001.jsonl") shouldBe ".npn/journal/dev1/0001.jsonl"
        NpnPaths.ocrSidecarPath("abc123", "dev1") shouldBe ".npn/ocr/abc123.dev1.json"
    }

    @Test
    fun trashPathKeepsExtensionAndHashPrefix() {
        NpnPaths.trashPath("Invoice.pdf", "deadbeefcafebabe0000") shouldBe ".npn/trash/Invoice__deadbeef.pdf"
        NpnPaths.trashPath("noext", "deadbeefcafebabe0000") shouldBe ".npn/trash/noext__deadbeef"
        NpnPaths.trashPath("a.b.pdf", "deadbeefcafebabe0000") shouldBe ".npn/trash/a.b__deadbeef.pdf"
    }

    @Test
    fun isInternal() {
        NpnPaths.isInternal(".npn").shouldBeTrue()
        NpnPaths.isInternal(".npn/journal/dev1/0.jsonl").shouldBeTrue()
        NpnPaths.isInternal("inbox/x.pdf").shouldBeFalse()
        NpnPaths.isInternal(".npnfoo/x").shouldBeFalse()
    }

    @Test
    fun isOwnDeviceWrite() {
        // Own journal dir + segments: self-echo, must be filtered.
        NpnPaths.isOwnDeviceWrite(".npn/journal/dev1", "dev1").shouldBeTrue()
        NpnPaths.isOwnDeviceWrite(".npn/journal/dev1/0001.jsonl", "dev1").shouldBeTrue()
        // Own OCR sidecar: self-echo.
        NpnPaths.isOwnDeviceWrite(".npn/ocr/abc123.dev1.json", "dev1").shouldBeTrue()
        // Another device's journal/sidecar (arriving via sync) must keep triggering a refresh.
        NpnPaths.isOwnDeviceWrite(".npn/journal/dev2/0001.jsonl", "dev1").shouldBeFalse()
        NpnPaths.isOwnDeviceWrite(".npn/ocr/abc123.dev2.json", "dev1").shouldBeFalse()
        // A device id that prefixes another must not match the longer one's dir.
        NpnPaths.isOwnDeviceWrite(".npn/journal/dev12/0001.jsonl", "dev1").shouldBeFalse()
        // Document-area and trash paths are never self-writes.
        NpnPaths.isOwnDeviceWrite("inbox/x.pdf", "dev1").shouldBeFalse()
        NpnPaths.isOwnDeviceWrite(".npn/trash/x__deadbeef.pdf", "dev1").shouldBeFalse()
    }

    @Test
    fun isHidden() {
        NpnPaths.isHidden(".npn/x").shouldBeTrue()
        NpnPaths.isHidden("a/.hidden/b.pdf").shouldBeTrue()
        NpnPaths.isHidden("a/b/c.pdf").shouldBeFalse()
    }

    @Test
    fun isPdf() {
        NpnPaths.isPdf("x.pdf").shouldBeTrue()
        NpnPaths.isPdf("X.PDF").shouldBeTrue()
        NpnPaths.isPdf("x.txt").shouldBeFalse()
        NpnPaths.isPdf("pdf").shouldBeFalse()
    }

    @Test
    fun isSyncConflictCoversEachFamily() {
        // syncthing
        NpnPaths.isSyncConflict("doc.sync-conflict-20260715-120000-ABCDEFG.pdf").shouldBeTrue()
        // Dropbox conflicted copy
        NpnPaths.isSyncConflict("doc (conflicted copy 2026-07-15).pdf").shouldBeTrue()
        // Dropbox case conflict
        NpnPaths.isSyncConflict("doc (Case Conflict).pdf").shouldBeTrue()
        // Nextcloud / ownCloud
        NpnPaths.isSyncConflict("doc (conflicted copy jeroen 2026-07-15).pdf").shouldBeTrue()
        // clean file
        NpnPaths.isSyncConflict("doc.pdf").shouldBeFalse()
    }

    @Test
    fun isSyncTemp() {
        NpnPaths.isSyncTemp("~syncthing~doc.pdf.tmp").shouldBeTrue()
        NpnPaths.isSyncTemp(".syncthing.doc.pdf.tmp").shouldBeTrue()
        NpnPaths.isSyncTemp("doc.pdf").shouldBeFalse()
    }

    @Test
    fun indexable() {
        fun file(rel: String) = FileMeta(rel, rel.substringAfterLast('/'), 1, 0, false)
        NpnPaths.indexable(file("inbox/doc.pdf")).shouldBeTrue()
        NpnPaths.indexable(file(".npn/ocr/x.json")).shouldBeFalse()
        NpnPaths.indexable(file("a/.hidden/doc.pdf")).shouldBeFalse()
        NpnPaths.indexable(file("doc.txt")).shouldBeFalse()
        NpnPaths.indexable(file("doc.sync-conflict-20260715-120000-ABCDEFG.pdf")).shouldBeFalse()
        NpnPaths.indexable(file("~syncthing~doc.pdf")).shouldBeFalse()
        NpnPaths.indexable(FileMeta("dir", "dir", 0, 0, true)).shouldBeFalse()
    }
}
