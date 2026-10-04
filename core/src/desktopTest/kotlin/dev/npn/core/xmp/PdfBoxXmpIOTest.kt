package dev.npn.core.xmp

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PdfBoxXmpIOTest {
    private val io = PdfBoxXmpIO()

    /** A minimal valid one-page PDF. */
    private fun fixturePdf(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun writeThenReadBackRoundtrips() =
        runTest {
            val data =
                xmp(
                    documentId = "d".repeat(64),
                    tags = listOf("invoice", "acme", "2026"),
                    title = "Baked Title",
                    correspondent = "ACME",
                    docType = "invoice",
                    docDate = "2026-07-15",
                    reviewed = "true",
                )
            val xmp = XmpSerializer.serialize(data)
            val baked = io.writeBaked(fixturePdf(), xmp, data.tags, data.title)

            io.readXmp(baked) shouldBe xmp
        }

    @Test
    fun docInfoKeywordsAndTitleSet() =
        runTest {
            val tags = listOf("a", "b", "c")
            val xmp = XmpSerializer.serialize(xmp(tags = tags, title = "Doc T"))
            val baked = io.writeBaked(fixturePdf(), xmp, tags, "Doc T")

            org.apache.pdfbox.Loader.loadPDF(baked).use { doc ->
                doc.documentInformation.keywords shouldBe "a, b, c"
                doc.documentInformation.title shouldBe "Doc T"
            }
        }

    @Test
    fun verifyTrueForBaked() =
        runTest {
            val data = xmp(documentId = "e".repeat(64), tags = listOf("x"))
            val baked = io.writeBaked(fixturePdf(), XmpSerializer.serialize(data), data.tags, null)
            io.verify(baked).shouldBeTrue()
        }

    @Test
    fun verifyFalseForPlainPdf() =
        runTest {
            io.verify(fixturePdf()).shouldBeFalse()
        }

    @Test
    fun writeBakedThrowsOnGarbage() =
        runTest {
            assertFailsWith<Throwable> {
                io.writeBaked("not a pdf".encodeToByteArray(), "<x/>", listOf("t"), null)
            }
        }

    @Test
    fun readXmpNullForPlainPdf() =
        runTest {
            io.readXmp(fixturePdf()) shouldBe null
        }

    @Test
    fun interopStringsPresentInMetadataStream() =
        runTest {
            val data = xmp(tags = listOf("interoptag"), documentId = "f".repeat(64))
            val xmp = XmpSerializer.serialize(data)
            val baked = io.writeBaked(fixturePdf(), xmp, data.tags, null)

            // Metadata streams are typically stored uncompressed. Check the raw bytes first; if the
            // strings are not literally present (compression), fall back to the exported XMP.
            val raw = baked.decodeToString()
            val rawHasSubject = raw.contains("dc:subject")
            val rawHasTag = raw.contains("interoptag")
            if (rawHasSubject && rawHasTag) {
                rawHasSubject.shouldBeTrue()
                rawHasTag.shouldBeTrue()
            } else {
                val exported = io.readXmp(baked)
                exported.shouldNotBeNull()
                exported.contains("dc:subject").shouldBeTrue()
                exported.contains("interoptag").shouldBeTrue()
            }
        }

    @Test
    fun exiftoolInteropIfAvailable() =
        runTest {
            val exiftool = findOnPath("exiftool") ?: return@runTest // skip gracefully
            val data = xmp(tags = listOf("exiftooltag"), title = "Exif Title")
            val xmp = XmpSerializer.serialize(data)
            val baked = io.writeBaked(fixturePdf(), xmp, data.tags, data.title)

            val tmp = java.io.File.createTempFile("npn-exif", ".pdf")
            try {
                tmp.writeBytes(baked)
                val proc =
                    ProcessBuilder(exiftool, "-Subject", "-Keywords", tmp.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                val output = proc.inputStream.readBytes().decodeToString()
                proc.waitFor()
                output.contains("exiftooltag").shouldBeTrue()
            } finally {
                tmp.delete()
            }
        }

    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(java.io.File.pathSeparator)) {
            val f = java.io.File(dir, name)
            if (f.canExecute()) return f.absolutePath
        }
        return null
    }
}
