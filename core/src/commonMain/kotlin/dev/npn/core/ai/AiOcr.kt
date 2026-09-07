package dev.npn.core.ai

import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngines
import kotlinx.coroutines.CancellationException

/**
 * Page transcription by a vision model on the configured AI endpoint — OCR done by the LLM instead
 * of a local engine, for scans a local engine reads badly (handwriting, tables, poor contrast,
 * scripts the bundled models do not cover).
 *
 * A separate facet from [AiSuggest] because it is a different bargain, not a different prompt:
 * suggestions cost one call per document off already-extracted text, this costs one call per
 * *page* and produces the text everything else reads. That is why nothing here ever starts itself
 * (see [AiConfig.ocrAuto] and `OcrPipeline`).
 */
interface AiOcr {
    /** True when the endpoint is configured well enough to run a transcription call. */
    suspend fun ocrConfigured(): Boolean

    /**
     * Transcribe one page, rendered as PNG, in [lang] (an OCR language code like `eng`, used as a
     * hint only — the model reads whatever the page is actually written in).
     *
     * Returns the page's text, or `""` for a page the model reports as carrying none. Throws on
     * endpoint/network errors, so a failed page fails its document's OCR run rather than silently
     * storing a hole in the archive's text.
     */
    suspend fun transcribePage(
        png: ByteArray,
        lang: String,
    ): String
}

/**
 * Ask [attempt] for one page's text up to [attempts] times, and turn what comes back into the
 * [AiOcr.transcribePage] contract. Separated from the endpoint call it wraps because this is the
 * part that decides how many paid calls a bad page costs, and that decision is worth testing
 * without a live model behind it.
 *
 * [attempt] reports three things, and each means something different here:
 *  - a string (including `""` for a page the model called blank) — an answer; stop.
 *  - `null` — nothing usable came back; ask again while attempts remain, then give up and return
 *    `""`, which is the same "no text on this page" a local engine reports for a page it cannot
 *    read.
 *  - a throw — the endpoint failed. Retry, but if every attempt threw, rethrow the last failure:
 *    the page's text is unknown, and publishing a write-once sidecar with a silent hole in it is
 *    worse than failing the run.
 *
 * [CancellationException] is never a failure to retry. It means the caller is gone, so it leaves
 * immediately rather than spending another call on a result nobody will read.
 */
internal suspend fun transcribeWithRetry(
    attempts: Int,
    attempt: suspend () -> String?,
): String {
    var failure: Throwable? = null
    repeat(attempts) {
        val outcome = runCatching { attempt() }
        outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        outcome.getOrNull()?.let { return it }
        failure = outcome.exceptionOrNull() ?: failure
    }
    failure?.let { throw it }
    return ""
}

/**
 * Adapts [AiOcr] to the [OcrEngine] the OCR pipeline consumes, so the vision endpoint is just
 * another engine to rank, select and store sidecars for — `.npn/ocr/<hash>.<device>.ai.json` is a
 * normal OCR version, and other replicas adopt it without needing an endpoint of their own.
 *
 * Thin on purpose: it exists so [OcrEngine] stays out of the AI contract surface (a chat facade
 * has no business having a `name` and an `available()`), and so the engine shares the facade's
 * cached HTTP client instead of building a second one.
 */
class AiOcrEngine(
    private val ocr: AiOcr,
) : OcrEngine {
    override val name: String = OcrEngines.AI

    override suspend fun available(): Boolean = ocr.ocrConfigured()

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String = ocr.transcribePage(png, lang)
}
