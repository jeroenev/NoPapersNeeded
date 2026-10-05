/**
 * Reads the bundled Tesseract model list (`desktopApp/tessdata-fast.txt`): one
 * `<language code> <sha256>` per line, in order; blank lines and `#` comments are skipped.
 */
object TessdataModels {
    private val LINE = Regex("""([a-z_]+)\s+([0-9a-f]{64})""")

    fun parse(text: String): Map<String, String> {
        val models = linkedMapOf<String, String>()
        text
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val (lang, sha256) =
                    requireNotNull(LINE.matchEntire(line)) { "Not '<language code> <sha256>': $line" }.destructured
                require(models.put(lang, sha256) == null) { "$lang is listed twice" }
            }
        return models
    }
}
