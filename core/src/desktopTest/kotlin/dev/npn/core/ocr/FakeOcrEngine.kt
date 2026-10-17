package dev.npn.core.ocr

/**
 * An OCR engine that records how many times it was invoked and returns canned text, or throws
 * [failure] while one is set (a cancellation included, as an engine torn down raises one).
 */
internal class FakeOcrEngine(
    private val isAvailable: Boolean,
    override val name: String,
    private val canned: String = "canned ocr text",
    /** What [languagesFor] resolves a request to; all of it by default, like a real engine. */
    private val installed: (String) -> String = { it },
) : OcrEngine {
    var calls = 0
        private set

    /** The `lang` of the last [recognizePng] call. */
    var lastLang: String? = null
        private set

    /** Thrown by every call while set, the way an engine reports a page it could not read. */
    var failure: Exception? = null

    override suspend fun available(): Boolean = isAvailable

    override suspend fun languagesFor(lang: String): String = installed(lang)

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        calls++
        lastLang = lang
        failure?.let { throw it }
        return canned
    }

    companion object {
        /** No engine at all: the pipeline uses embedded text only. */
        fun unavailable(): FakeOcrEngine = FakeOcrEngine(isAvailable = false, name = "noop", canned = "")
    }
}
