package dev.npn.core.pdf

import dev.npn.core.app.appSessionTest
import dev.npn.core.util.NpnLog
import io.kotest.matchers.shouldBe
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test

class CreationDatesTest {
    /**
     * A document gets the creation date its PDF names; one whose PDF names none, or that does not
     * open as a PDF, gets its file's mtime; one whose file no longer holds its bytes keeps waiting,
     * and asks for a rescan.
     */
    @Test
    fun eachDocumentGetsItsPdfsCreationDateElseItsFileTime() =
        appSessionTest { rig ->
            rig.clock.now = 400 * DAY
            rig.writePdf("dated.pdf", pdf(createdAt = 12 * DAY))
            rig.writePdf("undated.pdf", pdf(createdAt = null))
            rig.writePdf("broken.pdf", "%PDF-1.7 not really".toByteArray())
            rig.writePdf("rewritten.pdf", pdf(createdAt = 13 * DAY))
            for (name in listOf("dated.pdf", "undated.pdf", "broken.pdf", "rewritten.pdf")) {
                Files.setLastModifiedTime(rig.libRoot.resolve(name), FileTime.fromMillis(300 * DAY))
            }
            rig.session.rescan()
            val ids = listOf("dated.pdf", "undated.pdf", "broken.pdf", "rewritten.pdf").map { rig.fs.sha256(it) }
            // Written over since the scan: these bytes are not the document's.
            rig.writePdf("rewritten.pdf", pdf(createdAt = 14 * DAY))
            val dao = rig.db.documentDao()

            var stale = 0
            CreationDates(
                PdfBoxEngine(),
                rig.fs,
                dao,
                RenderGate(1),
                NpnLog.None,
                onStaleFile = { stale++ },
            ).fillMissing()

            ids.map { dao.getByDocId(it)?.createdAt } shouldBe listOf(12 * DAY, 300 * DAY, 300 * DAY, null)
            // The rewritten file asks for the rescan that sorts out what it holds now.
            stale shouldBe 1
        }

    private fun pdf(createdAt: Long?): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            // A distinct page count per call keeps each file's bytes, and so its document, apart.
            repeat(((createdAt ?: 0) / DAY).toInt()) { doc.addPage(PDPage()) }
            createdAt?.let {
                doc.documentInformation.creationDate =
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }
            }
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
