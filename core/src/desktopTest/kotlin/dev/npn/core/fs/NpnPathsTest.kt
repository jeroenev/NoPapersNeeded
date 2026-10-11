package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NpnPathsTest {
    @Test
    fun ocrPaths() {
        OcrSidecarName("abc123", "dev1").path shouldBe ".npn/ocr/abc123.dev1.json"
    }

    @Test
    fun pathParts() {
        NpnPaths.parentDir("a/b/c.pdf") shouldBe "a/b"
        NpnPaths.parentDir("c.pdf") shouldBe ""
        NpnPaths.fileName("a/b/c.pdf") shouldBe "c.pdf"
        NpnPaths.fileName("c.pdf") shouldBe "c.pdf"
    }

    @Test
    fun stripPdfExtensionIgnoresCaseAndKeepsOtherNames() {
        NpnPaths.stripPdfExtension("scan.pdf") shouldBe "scan"
        NpnPaths.stripPdfExtension("Scan.PDF") shouldBe "Scan"
        NpnPaths.stripPdfExtension("Scan.Pdf") shouldBe "Scan"
        NpnPaths.stripPdfExtension("report.2024.pdf") shouldBe "report.2024"
        NpnPaths.stripPdfExtension("notes.txt") shouldBe "notes.txt"
        NpnPaths.stripPdfExtension(".pdf") shouldBe ".pdf"
    }

    @Test
    fun engineQualifiedOcrPaths() {
        // writerKey composes the device+engine key; the sidecar's path treats it as an opaque
        // middle so the file lands at `<hash>.<deviceId>.<engine>.json`.
        val key = OcrSidecarName.writerKey("dev1", "tesseract")
        key shouldBe "dev1.tesseract"
        OcrSidecarName("abc123", key).path shouldBe ".npn/ocr/abc123.dev1.tesseract.json"
    }

    @Test
    fun trashPathKeepsExtensionAndHashPrefix() {
        NpnPaths.trashPath("Invoice.pdf", "deadbeefcafebabe0000", 1234) shouldBe
            ".npn/trash/Invoice__deadbeef-1234.pdf"
        NpnPaths.trashPath("noext", "deadbeefcafebabe0000", 1234) shouldBe ".npn/trash/noext__deadbeef-1234"
        NpnPaths.trashPath("a.b.pdf", "deadbeefcafebabe0000", 1234) shouldBe
            ".npn/trash/a.b__deadbeef-1234.pdf"
    }

    /** The episode round-trips: it is what tells one trash's entry from the next one's. */
    @Test
    fun trashEpisodeMillisRoundTripsAndReadsOldNames() {
        NpnPaths.trashEpisodeMillis(NpnPaths.trashPath("Invoice.pdf", "deadbeefcafebabe0000", 1234)) shouldBe 1234
        NpnPaths.trashEpisodeMillis(NpnPaths.trashPath("noext", "deadbeefcafebabe0000", 1234)) shouldBe 1234
        // An older build's plain name and its -N collision names: no episode, or one as old as any.
        NpnPaths.trashEpisodeMillis("Invoice__deadbeef.pdf") shouldBe null
        NpnPaths.trashEpisodeMillis("Invoice__deadbeef-2.pdf") shouldBe 2
        NpnPaths.trashEpisodeMillis("Invoice.pdf") shouldBe null
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
        // Own OCR sidecar: self-echo. Both legacy and engine-qualified forms.
        NpnPaths.isOwnDeviceWrite(".npn/ocr/abc123.dev1.json", "dev1").shouldBeTrue()
        NpnPaths.isOwnDeviceWrite(".npn/ocr/abc123.dev1.tesseract.json", "dev1").shouldBeTrue()
        // Another device's sidecar (arriving via sync) must keep triggering a refresh.
        NpnPaths.isOwnDeviceWrite(".npn/ocr/abc123.dev2.json", "dev1").shouldBeFalse()
        NpnPaths.isOwnDeviceWrite(".npn/ocr/abc123.dev2.tesseract.json", "dev1").shouldBeFalse()
        // This build writes no v1 segments, so one under this device's id came from elsewhere.
        NpnPaths.isOwnDeviceWrite(".npn/journal/dev1/0001.jsonl", "dev1").shouldBeFalse()
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
    fun sanitizeSegment() {
        NpnPaths.sanitizeSegment("invoice/July: final") shouldBe "invoice_July_ final"
        NpnPaths.sanitizeSegment("  padded  ") shouldBe "padded"
        NpnPaths.sanitizeSegment("already-fine") shouldBe "already-fine"
    }

    @Test
    fun sanitizeFileName() {
        NpnPaths.sanitizeFileName("Invoice: 2026?.pdf") shouldBe "Invoice_ 2026_.pdf"
        NpnPaths.sanitizeFileName("report.tar.gz") shouldBe "report.tar.gz"
        NpnPaths.sanitizeFileName("notes...pdf") shouldBe "notes.pdf"
        NpnPaths.sanitizeFileName("CON.pdf") shouldBe "_CON.pdf"
        NpnPaths.sanitizeFileName("nul") shouldBe "_nul"
        NpnPaths.sanitizeFileName("com5") shouldBe "_com5"
        NpnPaths.sanitizeFileName("Invoice 2026.pdf") shouldBe "Invoice 2026.pdf"
        NpnPaths.sanitizeFileName("x.") shouldBe "x"
        NpnPaths.sanitizeFileName("x..") shouldBe "x"
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
        // Nextcloud and ownCloud desktop clients, and Resilio Sync.
        NpnPaths.isSyncTemp(".doc.pdf.~3fa9c2d1").shouldBeTrue()
        NpnPaths.isSyncTemp("doc.pdf.!sync").shouldBeTrue()
        NpnPaths.isSyncTemp("doc.pdf").shouldBeFalse()
        NpnPaths.isSyncTemp(".hidden.pdf").shouldBeFalse()
        NpnPaths.isSyncTemp("doc.pdf.~3fa9c2d1").shouldBeFalse()
        NpnPaths.isSyncTemp(".doc.pdf.~backup").shouldBeFalse()
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
        // A name the app could not address on every replica is left alone, not indexed.
        NpnPaths.indexable(file("C:evil.pdf")).shouldBeFalse()
        NpnPaths.indexable(file("a\\b.pdf")).shouldBeFalse()
    }

    @Test
    fun normalizeToleratesSlashesButRefusesEscapes() {
        NpnPaths.normalize("/inbox//doc.pdf/") shouldBe "inbox/doc.pdf"
        NpnPaths.normalize("") shouldBe ""
        listOf(
            "../outside.pdf",
            "inbox/../../outside.pdf",
            "inbox/./doc.pdf",
            ".",
            "..",
            "inbox\\..\\..\\outside.pdf",
            "C:/Windows/system32",
            "c:relative.pdf",
            "inbox/doc\u0000.pdf",
        ).forEach { path ->
            NpnPaths.isSafe(path).shouldBeFalse()
            assertFailsWith<IllegalArgumentException>(path) { NpnPaths.normalize(path) }
        }
        // Dots inside a segment are ordinary; only the bare `.`/`..` segments traverse.
        NpnPaths.isSafe("inbox/...pdf").shouldBeTrue()
        NpnPaths.isSafe(".npn/journal/objects-v2-ab/x.jsonl").shouldBeTrue()
        NpnPaths.isSafe("Invoice 2024:01.pdf").shouldBeTrue()
    }

    @Test
    fun observationPredicatesNeverThrowOnUnsafePaths() {
        // The watcher and rescan run these over whatever is on disk; a hostile name must be
        // classified as "not ours", not kill the loop.
        NpnPaths.isInternal("../.npn/x").shouldBeFalse()
        NpnPaths.isHidden("a\\.b").shouldBeFalse()
        NpnPaths.isOwnDeviceWrite("../.npn/ocr/abc123.dev1.json", "dev1").shouldBeFalse()
    }
}
