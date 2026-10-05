package dev.npn.desktop

import dev.npn.core.ocr.OcrLanguages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * tessdata-fast.txt lists the Tesseract models a Windows build downloads. The Flatpak manifest
 * names the same models again, as flatpak-builder sources, and the OCR language picker offers them
 * as the bundled languages; this keeps both in step with the list.
 */
class BundledTessdataTest {
    // The test task runs in the project directory and passes the release the build downloads from.
    private val models: Map<String, String> = parseModels(File("tessdata-fast.txt").readText())
    private val baseUrl: String = System.getProperty("npn.tessdataFastBaseUrl")

    @Test
    fun theFlatpakBundlesTheListedModels() {
        val manifest = File("flatpak/io.github.jeroenev.NoPapersNeeded.yml").readText()
        val sources = MANIFEST_MODEL.findAll(manifest).map { it.destructured }.toList()

        assertEquals(models, sources.associate { (_, lang, sha256) -> lang to sha256 })
        assertEquals(listOf(baseUrl), sources.map { (url, _, _) -> url }.distinct())
    }

    @Test
    fun theOcrLanguagePickerOffersTheListedModels() {
        assertEquals(models.keys.toList(), OcrLanguages.BUNDLED)
    }

    private companion object {
        val MANIFEST_MODEL = Regex("""url: (\S+)/(\w+)\.traineddata\s+sha256: (\w+)""")

        fun parseModels(text: String): Map<String, String> =
            text
                .lines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .associate { line -> line.substringBefore(' ') to line.substringAfterLast(' ') }
    }
}
