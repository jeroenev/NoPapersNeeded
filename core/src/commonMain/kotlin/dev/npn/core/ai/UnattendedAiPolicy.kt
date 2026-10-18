package dev.npn.core.ai

/**
 * Whether this replica may spend the user's AI budget *without being asked*.
 *
 * Two paths in the app call a paid endpoint with nobody watching: automatic vision OCR
 * (`OcrEngineSelector`, one call per page of every document this replica OCRs) and automatic
 * enrichment (`AiAutoEnricher`, one call per document it just OCR'd). This is the one home for
 * that decision, so neither reads its toggle straight off [AiConfig].
 *
 * The answer is the user's own [AiConfig.ocrAuto] / [AiConfig.autoOnOcr] toggles, and only while
 * the endpoint is configured. A run the user asked for (a queued edit run, "OCR with AI" on one
 * document) does not consult it on the device it was asked on; a replica that syncs the resulting
 * engine pin was asked nothing, so there it is unattended work like any other.
 *
 * Both answers are read per call, so flipping a setting takes effect on the next document.
 */
internal class UnattendedAiPolicy(
    private val readConfig: suspend () -> AiConfig,
) {
    /**
     * May the vision model be this replica's automatic OCR engine? The engine's own
     * availability (`OcrEngine.available`) is checked by the selector; this is purely the
     * permission to spend.
     */
    suspend fun autoOcr(): Boolean = readConfig().let { it.configured && it.ocrAuto }

    /** May a document this replica just OCR'd be enriched (tags, empty fields) without review? */
    suspend fun autoEnrich(): Boolean = readConfig().let { it.configured && it.autoOnOcr }
}
